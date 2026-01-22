package com.nationsbenefits.service;

import com.nationsbenefits.model.Lock;
import com.nationsbenefits.model.LockError;
import com.nationsbenefits.model.LockResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Thread-safe in-memory storage for distributed locks.
 * Uses virtual threads for lock expiration cleanup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LockStore {
    private static final Duration CLEANUP_INTERVAL = Duration.ofSeconds(1);

    private final Map<String, Lock> locks = new ConcurrentHashMap<>();
    private final FencingTokenGenerator tokenGenerator;
    private ScheduledExecutorService cleanupExecutor;

    @PostConstruct
    public void startCleanupTask() {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("lock-cleanup-", 0).factory()
        );
        cleanupExecutor.scheduleAtFixedRate(
                this::cleanupExpiredLocks,
                CLEANUP_INTERVAL.toMillis(),
                CLEANUP_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS
        );
        log.info("Lock cleanup task started with interval: {}", CLEANUP_INTERVAL);
    }

    @PreDestroy
    public void stopCleanupTask() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Attempts to acquire a lock.
     *
     * @param lockId       The lock identifier
     * @param clientId     The client requesting the lock
     * @param regionId     The region of the client
     * @param timeoutMs    Lock timeout in milliseconds
     * @return LockResult containing the Lock if successful, or error if not
     */
    public LockResult<Lock> tryAcquire(String lockId, String clientId, String regionId, long timeoutMs) {
        return computeIfAbsentOrExpired(lockId, existing -> {
            long token = tokenGenerator.nextToken(lockId);
            return Lock.create(lockId, clientId, regionId, token, timeoutMs);
        });
    }

    /**
     * Acquires a lock with a pre-determined fencing token (used for distributed consensus).
     */
    public LockResult<Lock> acquireWithToken(String lockId, String clientId, String regionId,
                                              long fencingToken, Instant expiresAt) {
        return computeIfAbsentOrExpired(lockId, existing ->
            new Lock(lockId, clientId, regionId, fencingToken, Instant.now(), expiresAt)
        );
    }

    private LockResult<Lock> computeIfAbsentOrExpired(String lockId, Function<Lock, Lock> lockCreator) {
        var result = new LockResult[] { null };

        locks.compute(lockId, (key, existing) -> {
            if (existing == null || existing.isExpired()) {
                Lock newLock = lockCreator.apply(existing);
                result[0] = LockResult.success(newLock);
                log.debug("Lock acquired: {} by {}", lockId, newLock.holderId());
                return newLock;
            } else {
                result[0] = LockResult.failure(
                        LockError.alreadyLocked(existing.holderId(), existing.fencingToken())
                );
                return existing;
            }
        });

        return result[0];
    }

    /**
     * Releases a lock if the fencing token matches.
     *
     * @param lockId        The lock identifier
     * @param clientId      The client releasing the lock
     * @param fencingToken  The fencing token from acquisition
     * @return LockResult indicating success or failure
     */
    public LockResult<Void> release(String lockId, String clientId, long fencingToken) {
        var result = new LockResult[] { null };

        locks.compute(lockId, (key, existing) -> {
            if (existing == null) {
                result[0] = LockResult.failure(LockError.notFound(lockId));
                return null;
            }

            if (existing.isExpired()) {
                result[0] = LockResult.failure(LockError.expired(lockId));
                return null;
            }

            if (!existing.matchesToken(fencingToken)) {
                result[0] = LockResult.failure(
                        LockError.invalidToken(existing.fencingToken(), fencingToken)
                );
                return existing;
            }

            if (!existing.holderId().equals(clientId)) {
                result[0] = LockResult.failure(LockError.error(
                        "Lock is held by different client: " + existing.holderId()
                ));
                return existing;
            }

            log.debug("Lock released: {} by {}", lockId, clientId);
            result[0] = LockResult.success(null);
            return null;
        });

        return result[0];
    }

    /**
     * Releases a lock by fencing token only (used for distributed release).
     */
    public LockResult<Void> releaseByToken(String lockId, long fencingToken) {
        var result = new LockResult[] { null };

        locks.compute(lockId, (key, existing) -> {
            if (existing == null) {
                result[0] = LockResult.success(null);
                return null;
            }

            if (!existing.matchesToken(fencingToken)) {
                result[0] = LockResult.failure(
                        LockError.invalidToken(existing.fencingToken(), fencingToken)
                );
                return existing;
            }

            log.debug("Lock released by token: {}", lockId);
            result[0] = LockResult.success(null);
            return null;
        });

        return result[0];
    }

    /**
     * Gets the current state of a lock.
     */
    public Optional<Lock> get(String lockId) {
        return Optional.ofNullable(locks.get(lockId))
                .filter(lock -> !lock.isExpired());
    }

    /**
     * Checks if a lock exists and is not expired.
     */
    public boolean isLocked(String lockId) {
        return get(lockId).isPresent();
    }

    /**
     * Gets all active (non-expired) locks.
     */
    public Collection<Lock> getAllActiveLocks() {
        return locks.values().stream()
                .filter(lock -> !lock.isExpired())
                .toList();
    }

    /**
     * Gets the count of active locks.
     */
    public int getActiveLockCount() {
        return (int) locks.values().stream()
                .filter(lock -> !lock.isExpired())
                .count();
    }

    /**
     * Forces the removal of a lock (for administrative purposes).
     */
    public void forceRemove(String lockId) {
        locks.remove(lockId);
        log.warn("Lock forcibly removed: {}", lockId);
    }

    /**
     * Clears all locks (for testing).
     */
    public void clear() {
        locks.clear();
        log.warn("All locks cleared");
    }

    private void cleanupExpiredLocks() {
        var expiredCount = locks.entrySet().stream()
                .filter(entry -> entry.getValue().isExpired())
                .peek(entry -> log.debug("Cleaning up expired lock: {}", entry.getKey()))
                .map(Map.Entry::getKey)
                .peek(locks::remove)
                .count();

        if (expiredCount > 0) {
            log.debug("Cleaned up {} expired locks", expiredCount);
        }
    }
}
