package com.nationsbenefits.service;

import com.nationsbenefits.config.LockConfig;
import com.nationsbenefits.config.RegionConfig;
import com.nationsbenefits.model.*;
import com.nationsbenefits.quorum.QuorumManager;
import com.nationsbenefits.raft.LockCommand;
import com.nationsbenefits.raft.LogEntryType;
import com.nationsbenefits.raft.RaftNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Main lock service coordinating between Raft consensus and cross-region quorum.
 * This is the central orchestrator for all lock operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LockService {

    private final RaftNode raftNode;
    private final QuorumManager quorumManager;
    private final LockStore lockStore;
    private final FencingTokenGenerator tokenGenerator;
    private final LockConfig lockConfig;
    private final RegionConfig regionConfig;

    /**
     * Attempts to acquire a distributed lock.
     * This involves:
     * 1. Checking if this node is the Raft leader (or forwarding to leader)
     * 2. Requesting votes from other regions (cross-region quorum)
     * 3. If quorum achieved, committing the lock via Raft
     */
    public CompletableFuture<LockResult<Lock>> acquireLock(String lockId, String clientId, long timeoutMs) {
        log.debug("Acquire lock request: lockId={}, clientId={}, timeout={}",
                lockId, clientId, timeoutMs);

        // Validate inputs
        if (!Lock.isValidLockId(lockId)) {
            return CompletableFuture.completedFuture(
                    LockResult.failure(LockStatus.ERROR, "Invalid lock ID format")
            );
        }

        // Normalize timeout
        long normalizedTimeout = lockConfig.normalizeTimeout(timeoutMs);

        // Check if we're the leader
        if (!raftNode.isLeader()) {
            return CompletableFuture.completedFuture(
                    LockResult.failure(LockError.notLeader(
                            raftNode.getLeaderId().orElse("unknown")
                    ))
            );
        }

        // Generate fencing token
        long fencingToken = tokenGenerator.nextToken(lockId);
        String regionId = regionConfig.getRegionId();

        // Request quorum from other regions
        return quorumManager.requestLockQuorum(lockId, clientId, fencingToken, normalizedTimeout)
                .thenCompose(quorumResult -> {
                    if (!quorumResult.isSuccess()) {
                        return CompletableFuture.completedFuture(
                                LockResult.<Lock>failure(quorumResult.getError())
                        );
                    }

                    // Quorum achieved, commit via Raft
                    var command = LockCommand.acquire(
                            lockId, clientId, regionId, fencingToken, normalizedTimeout
                    );

                    return raftNode.submit(LogEntryType.ACQUIRE_LOCK, command)
                            .thenApply(raftResult -> {
                                if (raftResult.isSuccess()) {
                                    // Notify other regions of successful acquisition
                                    quorumManager.notifyLockAcquired(
                                            lockId, regionId, clientId, fencingToken,
                                            System.currentTimeMillis() + normalizedTimeout
                                    );

                                    return LockResult.success(Lock.create(
                                            lockId, clientId, regionId, fencingToken, normalizedTimeout
                                    ));
                                }
                                return LockResult.<Lock>failure(raftResult.getError());
                            })
                            .orTimeout(regionConfig.getQuorumTimeoutMs(), TimeUnit.MILLISECONDS)
                            .exceptionally(e -> LockResult.failure(
                                    LockError.timeout("Raft commit: " + e.getMessage())
                            ));
                })
                .exceptionally(e -> {
                    log.error("Failed to acquire lock {}: {}", lockId, e.getMessage());
                    return LockResult.failure(LockError.error(e.getMessage()));
                });
    }

    /**
     * Releases a previously acquired lock.
     */
    public CompletableFuture<LockResult<Void>> releaseLock(String lockId, String clientId, long fencingToken) {
        log.debug("Release lock request: lockId={}, clientId={}, token={}",
                lockId, clientId, fencingToken);

        // Check if we're the leader
        if (!raftNode.isLeader()) {
            return CompletableFuture.completedFuture(
                    LockResult.failure(LockError.notLeader(
                            raftNode.getLeaderId().orElse("unknown")
                    ))
            );
        }

        // Verify the lock exists and is held by this client
        var existingLock = lockStore.get(lockId);
        if (existingLock.isEmpty()) {
            return CompletableFuture.completedFuture(
                    LockResult.failure(LockError.notFound(lockId))
            );
        }

        var lock = existingLock.get();
        if (!lock.matchesToken(fencingToken)) {
            return CompletableFuture.completedFuture(
                    LockResult.failure(LockError.invalidToken(lock.fencingToken(), fencingToken))
            );
        }

        if (!lock.holderId().equals(clientId)) {
            return CompletableFuture.completedFuture(
                    LockResult.failure(LockStatus.ERROR, "Lock not held by this client")
            );
        }

        // Commit release via Raft
        var command = LockCommand.release(lockId, clientId, fencingToken);

        return raftNode.submit(LogEntryType.RELEASE_LOCK, command)
                .thenApply(raftResult -> {
                    if (raftResult.isSuccess()) {
                        // Notify other regions
                        quorumManager.notifyLockReleased(lockId, fencingToken);
                        return LockResult.<Void>success(null);
                    }
                    return LockResult.<Void>failure(raftResult.getError());
                })
                .orTimeout(regionConfig.getQuorumTimeoutMs(), TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    log.error("Failed to release lock {}: {}", lockId, e.getMessage());
                    return LockResult.failure(LockError.timeout("Raft commit: " + e.getMessage()));
                });
    }

    /**
     * Checks the status of a lock.
     */
    public LockResult<LockInfo> checkLock(String lockId) {
        log.debug("Check lock request: lockId={}", lockId);

        return lockStore.get(lockId)
                .map(lock -> LockResult.success(new LockInfo(
                        lock.lockId(),
                        true,
                        lock.holderId(),
                        lock.holderRegion(),
                        lock.fencingToken(),
                        lock.ttlMs(),
                        lock.expiresAt().toEpochMilli()
                )))
                .orElseGet(() -> LockResult.failure(LockError.notFound(lockId)));
    }

    /**
     * Information about a lock's current state.
     */
    public record LockInfo(
            String lockId,
            boolean isLocked,
            String holderId,
            String holderRegion,
            long fencingToken,
            long ttlMs,
            long expiresAt
    ) {}
}
