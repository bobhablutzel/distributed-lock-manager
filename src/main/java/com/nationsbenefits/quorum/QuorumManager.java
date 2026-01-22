package com.nationsbenefits.quorum;

import com.nationsbenefits.config.RegionConfig;
import com.nationsbenefits.model.LockError;
import com.nationsbenefits.model.LockResult;
import com.nationsbenefits.raft.LockCommand;
import com.nationsbenefits.raft.LogEntryType;
import com.nationsbenefits.raft.RaftNode;
import com.nationsbenefits.service.LockStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages cross-region quorum voting for distributed locks.
 * Coordinates with other regional leaders to achieve consensus.
 *
 * Uses pending vote tracking to prevent race conditions where two regions
 * could simultaneously acquire the same lock.
 */
@Slf4j
@Component
public class QuorumManager {

    private static final long PENDING_VOTE_TIMEOUT_MS = 10000; // 10 seconds

    private final RegionConfig regionConfig;
    private final LockStore lockStore;
    private final RaftNode raftNode;
    private final Map<String, CrossRegionClient> regionClients = new ConcurrentHashMap<>();

    // Track pending votes - lockId -> PendingVote
    // When we grant a vote for a lock, we reserve it here to prevent granting votes
    // to other regions for the same lock until the acquisition is confirmed or times out
    private final Map<String, PendingVote> pendingVotes = new ConcurrentHashMap<>();

    private ExecutorService executor;
    private ScheduledExecutorService cleanupScheduler;

    public QuorumManager(RegionConfig regionConfig, LockStore lockStore, RaftNode raftNode) {
        this.regionConfig = regionConfig;
        this.lockStore = lockStore;
        this.raftNode = raftNode;
    }

    @PostConstruct
    public void init() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("pending-vote-cleanup-").factory()
        );

        // Start cleanup task for expired pending votes
        cleanupScheduler.scheduleAtFixedRate(
                this::cleanupExpiredPendingVotes,
                PENDING_VOTE_TIMEOUT_MS,
                PENDING_VOTE_TIMEOUT_MS / 2,
                TimeUnit.MILLISECONDS
        );

        // Initialize clients for peer regions
        for (var peer : regionConfig.getPeers()) {
            var client = new CrossRegionClient(peer.getHost(), peer.getPort(), peer.getRegionId());
            regionClients.put(peer.getRegionId(), client);
            log.info("Initialized cross-region client for {}", peer.getRegionId());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
        }
        for (var client : regionClients.values()) {
            client.shutdown();
        }
    }

    private void cleanupExpiredPendingVotes() {
        var now = Instant.now();
        pendingVotes.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAt().isBefore(now)) {
                log.debug("Removing expired pending vote for lock {} from region {}",
                        entry.getKey(), entry.getValue().requestingRegion());
                return true;
            }
            return false;
        });
    }

    /**
     * Requests a quorum from other regions to acquire a lock.
     * Returns success if a majority of regions agree to the lock acquisition.
     */
    public CompletableFuture<LockResult<QuorumResult>> requestLockQuorum(
            String lockId, String clientId, long fencingToken, long timeoutMs) {

        int totalRegions = regionConfig.getTotalRegions();
        int quorumNeeded = regionConfig.getQuorumSize();

        log.debug("Requesting quorum for lock {}: need {}/{} votes",
                lockId, quorumNeeded, totalRegions);

        // Check if lock is already held locally
        if (lockStore.isLocked(lockId)) {
            var existingLock = lockStore.get(lockId);
            return CompletableFuture.completedFuture(
                    LockResult.failure(LockError.alreadyLocked(
                            existingLock.map(l -> l.holderId()).orElse("unknown"),
                            existingLock.map(l -> l.fencingToken()).orElse(0L)
                    ))
            );
        }

        // Check if there's a pending vote for this lock from another region
        // This prevents race conditions where we could grant a vote to another region
        // while simultaneously trying to acquire the same lock ourselves
        var existingPending = pendingVotes.get(lockId);
        if (existingPending != null && existingPending.expiresAt().isAfter(Instant.now())) {
            log.debug("Cannot acquire lock {} - pending vote exists for region {}",
                    lockId, existingPending.requestingRegion());
            return CompletableFuture.completedFuture(
                    LockResult.failure(LockError.alreadyLocked(
                            existingPending.clientId(),
                            0L  // No token yet, acquisition pending
                    ))
            );
        }

        // Self-vote granted
        AtomicInteger votesReceived = new AtomicInteger(1);

        // If we're the only region, we have quorum
        if (totalRegions == 1) {
            return CompletableFuture.completedFuture(
                    LockResult.success(new QuorumResult(1, 1, List.of(regionConfig.getRegionId())))
            );
        }

        // Request votes from peer regions
        var request = new LockVoteRequest(
                lockId,
                regionConfig.getRegionId(),
                clientId,
                timeoutMs,
                fencingToken,
                System.currentTimeMillis()
        );

        List<CompletableFuture<VoteResult>> voteFutures = regionClients.values().stream()
                .map(client -> requestVoteFromRegion(client, request))
                .toList();

        return CompletableFuture.allOf(voteFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<String> grantingRegions = new CopyOnWriteArrayList<>();
                    grantingRegions.add(regionConfig.getRegionId()); // Add self

                    for (var future : voteFutures) {
                        try {
                            var result = future.join();
                            if (result.granted()) {
                                votesReceived.incrementAndGet();
                                grantingRegions.add(result.regionId());
                            }
                        } catch (Exception e) {
                            log.warn("Vote request failed: {}", e.getMessage());
                        }
                    }

                    int votes = votesReceived.get();
                    if (votes >= quorumNeeded) {
                        log.debug("Quorum achieved for lock {}: {}/{} votes",
                                lockId, votes, totalRegions);
                        return LockResult.success(new QuorumResult(votes, totalRegions, grantingRegions));
                    } else {
                        log.debug("Quorum failed for lock {}: {}/{} votes (needed {})",
                                lockId, votes, totalRegions, quorumNeeded);
                        return LockResult.<QuorumResult>failure(
                                LockError.quorumFailed(votes, quorumNeeded)
                        );
                    }
                })
                .orTimeout(regionConfig.getQuorumTimeoutMs(), TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    log.error("Quorum request timed out for lock {}", lockId);
                    return LockResult.failure(LockError.timeout("Quorum request"));
                });
    }

    private CompletableFuture<VoteResult> requestVoteFromRegion(
            CrossRegionClient client, LockVoteRequest request) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                var response = client.requestLockVote(request);
                return new VoteResult(
                        response.granted(),
                        client.getRegionId(),
                        response.denialReason()
                );
            } catch (Exception e) {
                log.warn("Failed to get vote from region {}: {}",
                        client.getRegionId(), e.getMessage());
                return new VoteResult(false, client.getRegionId(), e.getMessage());
            }
        }, executor);
    }

    /**
     * Handles a lock vote request from another region.
     * Uses pending vote tracking to prevent race conditions.
     */
    public LockVoteResponse handleLockVoteRequest(LockVoteRequest request) {
        log.debug("Received vote request from {} for lock {}",
                request.requestingRegion(), request.lockId());

        String lockId = request.lockId();

        // Check if lock is already held
        var existingLock = lockStore.get(lockId);
        if (existingLock.isPresent()) {
            var lock = existingLock.get();
            return new LockVoteResponse(
                    false,
                    lock.holderRegion(),
                    lock.holderId(),
                    lock.fencingToken(),
                    lock.expiresAt().toEpochMilli(),
                    regionConfig.getRegionId(),
                    "Lock already held"
            );
        }

        // Check if there's already a pending vote for this lock
        var existingPending = pendingVotes.get(lockId);
        if (existingPending != null && existingPending.expiresAt().isAfter(Instant.now())) {
            // There's already a pending vote for this lock
            if (existingPending.requestingRegion().equals(request.requestingRegion())) {
                // Same region requesting again, allow it (retry scenario)
                log.debug("Re-granting vote for lock {} to same region {}", lockId, request.requestingRegion());
            } else {
                // Different region - deny to prevent race condition
                log.debug("Denying vote for lock {} - pending vote exists for region {}",
                        lockId, existingPending.requestingRegion());
                return new LockVoteResponse(
                        false,
                        existingPending.requestingRegion(),
                        null,
                        0,
                        existingPending.expiresAt().toEpochMilli(),
                        regionConfig.getRegionId(),
                        "Pending vote for another region"
                );
            }
        }

        // Grant the vote and track it as pending
        var pendingVote = new PendingVote(
                request.requestingRegion(),
                request.clientId(),
                Instant.now().plusMillis(PENDING_VOTE_TIMEOUT_MS)
        );
        pendingVotes.put(lockId, pendingVote);
        log.debug("Granted vote for lock {} to region {}, pending until {}",
                lockId, request.requestingRegion(), pendingVote.expiresAt());

        return new LockVoteResponse(
                true,
                null, null, 0, 0,
                regionConfig.getRegionId(),
                null
        );
    }

    /**
     * Notifies other regions that a lock has been acquired.
     */
    public void notifyLockAcquired(String lockId, String holderRegion, String holderId,
                                   long fencingToken, long expiresAt) {
        var notification = new LockAcquiredNotification(
                lockId, holderRegion, holderId, fencingToken, expiresAt, regionConfig.getRegionId()
        );

        regionClients.values().forEach(client ->
                CompletableFuture.runAsync(() -> {
                    try {
                        client.notifyLockAcquired(notification);
                    } catch (Exception e) {
                        log.warn("Failed to notify {} of lock acquisition: {}",
                                client.getRegionId(), e.getMessage());
                    }
                }, executor)
        );
    }

    /**
     * Handles a lock acquired notification from another region.
     * Clears any pending vote and replicates the lock via Raft to all nodes in this region.
     */
    public void handleLockAcquiredNotification(LockAcquiredNotification notification) {
        log.debug("Received lock acquired notification: {} held by {}",
                notification.lockId(), notification.holderRegion());

        // Clear the pending vote for this lock
        var removed = pendingVotes.remove(notification.lockId());
        if (removed != null) {
            log.debug("Cleared pending vote for lock {} (acquired by {})",
                    notification.lockId(), notification.holderRegion());
        }

        // Replicate the lock acquisition via Raft so all nodes in this region have it
        if (raftNode.isLeader()) {
            long timeoutMs = notification.expiresAt() - System.currentTimeMillis();
            var command = LockCommand.acquire(
                    notification.lockId(),
                    notification.holderId(),
                    notification.holderRegion(),
                    notification.fencingToken(),
                    Math.max(timeoutMs, 1000) // Ensure at least 1 second timeout
            );

            raftNode.submit(LogEntryType.ACQUIRE_LOCK, command)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.error("Failed to replicate remote lock acquisition via Raft: {}",
                                    error.getMessage());
                        } else if (result.isSuccess()) {
                            log.debug("Replicated remote lock {} via Raft", notification.lockId());
                        } else {
                            log.warn("Remote lock replication returned error: {}",
                                    result.getError().message());
                        }
                    });
        } else {
            // Not leader - store locally (this shouldn't happen due to forwarding, but fallback)
            log.warn("Received lock acquired notification but not leader - storing locally only");
            lockStore.acquireWithToken(
                    notification.lockId(),
                    notification.holderId(),
                    notification.holderRegion(),
                    notification.fencingToken(),
                    Instant.ofEpochMilli(notification.expiresAt())
            );
        }
    }

    /**
     * Notifies other regions that a lock has been released.
     */
    public void notifyLockReleased(String lockId, long fencingToken) {
        var notification = new LockReleasedNotification(
                lockId, fencingToken, regionConfig.getRegionId()
        );

        regionClients.values().forEach(client ->
                CompletableFuture.runAsync(() -> {
                    try {
                        client.notifyLockReleased(notification);
                    } catch (Exception e) {
                        log.warn("Failed to notify {} of lock release: {}",
                                client.getRegionId(), e.getMessage());
                    }
                }, executor)
        );
    }

    /**
     * Handles a lock released notification from another region.
     * Clears any pending vote and replicates the release via Raft.
     */
    public void handleLockReleasedNotification(LockReleasedNotification notification) {
        log.debug("Received lock released notification: {}", notification.lockId());

        // Clear any pending vote for this lock
        pendingVotes.remove(notification.lockId());

        // Replicate the lock release via Raft so all nodes in this region remove it
        if (raftNode.isLeader()) {
            var command = LockCommand.release(
                    notification.lockId(),
                    null, // clientId not needed for release by token
                    notification.fencingToken()
            );

            raftNode.submit(LogEntryType.RELEASE_LOCK, command)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.error("Failed to replicate remote lock release via Raft: {}",
                                    error.getMessage());
                        } else {
                            log.debug("Replicated remote lock release {} via Raft", notification.lockId());
                        }
                    });
        } else {
            // Not leader - release locally (this shouldn't happen due to forwarding, but fallback)
            log.warn("Received lock released notification but not leader - releasing locally only");
            lockStore.releaseByToken(notification.lockId(), notification.fencingToken());
        }
    }

    // Record types for quorum communication
    public record LockVoteRequest(
            String lockId,
            String requestingRegion,
            String clientId,
            long proposedTimeoutMs,
            long proposedFencingToken,
            long requestTimestamp
    ) {}

    public record LockVoteResponse(
            boolean granted,
            String currentHolderRegion,
            String currentHolderClient,
            long currentFencingToken,
            long currentExpiresAt,
            String respondingRegion,
            String denialReason
    ) {}

    public record LockAcquiredNotification(
            String lockId,
            String holderRegion,
            String holderId,
            long fencingToken,
            long expiresAt,
            String notifyingRegion
    ) {}

    public record LockReleasedNotification(
            String lockId,
            long fencingToken,
            String notifyingRegion
    ) {}

    public record QuorumResult(
            int votesReceived,
            int totalRegions,
            List<String> grantingRegions
    ) {}

    private record VoteResult(boolean granted, String regionId, String reason) {}

    /**
     * Tracks a pending vote that has been granted but not yet confirmed.
     * Used to prevent race conditions in cross-region lock acquisition.
     */
    private record PendingVote(
            String requestingRegion,
            String clientId,
            Instant expiresAt
    ) {}
}
