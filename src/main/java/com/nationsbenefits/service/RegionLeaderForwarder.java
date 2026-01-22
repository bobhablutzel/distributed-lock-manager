package com.nationsbenefits.service;

import com.nationsbenefits.config.RaftConfig;
import com.nationsbenefits.grpc.generated.*;
import com.nationsbenefits.raft.RaftNode;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Forwards cross-region requests to the current Raft leader within this region.
 * Used when a follower receives a cross-region request that should be handled by the leader.
 */
@Slf4j
@Component
public class RegionLeaderForwarder {

    private final RaftNode raftNode;
    private final RaftConfig raftConfig;
    private final Map<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();
    private final Map<String, RegionServiceGrpc.RegionServiceBlockingStub> stubCache = new ConcurrentHashMap<>();

    public RegionLeaderForwarder(RaftNode raftNode, RaftConfig raftConfig) {
        this.raftNode = raftNode;
        this.raftConfig = raftConfig;
    }

    /**
     * Forwards a lock vote request to the current leader.
     */
    public Optional<LockVoteResponse> forwardLockVoteRequest(LockVoteRequest request) {
        return getLeaderStub().map(stub -> {
            try {
                log.debug("Forwarding lock vote request to leader for lock {}", request.getLockId());
                return stub.withDeadlineAfter(5, TimeUnit.SECONDS).requestLockVote(request);
            } catch (StatusRuntimeException e) {
                log.warn("Failed to forward lock vote request to leader: {}", e.getStatus());
                return null;
            }
        });
    }

    /**
     * Forwards a lock acquired notification to the current leader.
     */
    public Optional<NotificationAck> forwardLockAcquiredNotification(LockAcquiredNotification notification) {
        return getLeaderStub().map(stub -> {
            try {
                log.debug("Forwarding lock acquired notification to leader for lock {}", notification.getLockId());
                return stub.withDeadlineAfter(5, TimeUnit.SECONDS).notifyLockAcquired(notification);
            } catch (StatusRuntimeException e) {
                log.warn("Failed to forward lock acquired notification to leader: {}", e.getStatus());
                return null;
            }
        });
    }

    /**
     * Forwards a lock released notification to the current leader.
     */
    public Optional<NotificationAck> forwardLockReleasedNotification(LockReleasedNotification notification) {
        return getLeaderStub().map(stub -> {
            try {
                log.debug("Forwarding lock released notification to leader for lock {}", notification.getLockId());
                return stub.withDeadlineAfter(5, TimeUnit.SECONDS).notifyLockReleased(notification);
            } catch (StatusRuntimeException e) {
                log.warn("Failed to forward lock released notification to leader: {}", e.getStatus());
                return null;
            }
        });
    }

    /**
     * Gets a gRPC stub for the current leader.
     */
    private Optional<RegionServiceGrpc.RegionServiceBlockingStub> getLeaderStub() {
        Optional<String> leaderId = raftNode.getLeaderId();
        if (leaderId.isEmpty()) {
            log.warn("No leader available for forwarding");
            return Optional.empty();
        }

        String leader = leaderId.get();

        // Don't forward to self
        if (leader.equals(raftConfig.getNodeId())) {
            log.warn("Leader is self, cannot forward");
            return Optional.empty();
        }

        // Find leader's address from config
        var leaderNode = raftConfig.getPeerNodes().stream()
                .filter(node -> node.getNodeId().equals(leader))
                .findFirst();

        // Also check clusterNodes in case peer parsing doesn't include leader
        if (leaderNode.isEmpty()) {
            leaderNode = raftConfig.getClusterNodes().stream()
                    .filter(node -> node.getNodeId().equals(leader))
                    .findFirst();
        }

        if (leaderNode.isEmpty()) {
            log.warn("Leader {} not found in cluster configuration", leader);
            return Optional.empty();
        }

        var node = leaderNode.get();
        return Optional.of(getOrCreateStub(node.getNodeId(), node.getHost(), node.getPort()));
    }

    private RegionServiceGrpc.RegionServiceBlockingStub getOrCreateStub(String nodeId, String host, int port) {
        return stubCache.computeIfAbsent(nodeId, id -> {
            ManagedChannel channel = channelCache.computeIfAbsent(nodeId, cid ->
                    ManagedChannelBuilder.forAddress(host, port)
                            .usePlaintext()
                            .keepAliveTime(30, TimeUnit.SECONDS)
                            .build()
            );
            log.info("Created region forwarding channel to {} at {}:{}", nodeId, host, port);
            return RegionServiceGrpc.newBlockingStub(channel);
        });
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down region leader forwarder channels");
        for (var entry : channelCache.entrySet()) {
            try {
                entry.getValue().shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                entry.getValue().shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        channelCache.clear();
        stubCache.clear();
    }
}
