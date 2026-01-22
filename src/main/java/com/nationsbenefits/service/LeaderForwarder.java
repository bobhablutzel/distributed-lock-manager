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
 * Forwards lock requests to the current Raft leader.
 * Maintains gRPC connections to peer nodes for request forwarding.
 */
@Slf4j
@Component
public class LeaderForwarder {

    private final RaftNode raftNode;
    private final RaftConfig raftConfig;
    private final Map<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();
    private final Map<String, LockServiceGrpc.LockServiceBlockingStub> stubCache = new ConcurrentHashMap<>();

    public LeaderForwarder(RaftNode raftNode, RaftConfig raftConfig) {
        this.raftNode = raftNode;
        this.raftConfig = raftConfig;
    }

    /**
     * Forwards an acquire lock request to the current leader.
     */
    public Optional<AcquireLockResponse> forwardAcquireLock(AcquireLockRequest request) {
        return getLeaderStub().map(stub -> {
            try {
                log.debug("Forwarding acquire lock request to leader for lock {}", request.getLockId());
                // Apply fresh deadline for each request
                return stub.withDeadlineAfter(10, TimeUnit.SECONDS).acquireLock(request);
            } catch (StatusRuntimeException e) {
                log.warn("Failed to forward acquire lock to leader: {}", e.getStatus());
                return null;
            }
        });
    }

    /**
     * Forwards a release lock request to the current leader.
     */
    public Optional<ReleaseLockResponse> forwardReleaseLock(ReleaseLockRequest request) {
        return getLeaderStub().map(stub -> {
            try {
                log.debug("Forwarding release lock request to leader for lock {}", request.getLockId());
                // Apply fresh deadline for each request
                return stub.withDeadlineAfter(10, TimeUnit.SECONDS).releaseLock(request);
            } catch (StatusRuntimeException e) {
                log.warn("Failed to forward release lock to leader: {}", e.getStatus());
                return null;
            }
        });
    }

    /**
     * Gets a gRPC stub for the current leader.
     */
    private Optional<LockServiceGrpc.LockServiceBlockingStub> getLeaderStub() {
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

    private LockServiceGrpc.LockServiceBlockingStub getOrCreateStub(String nodeId, String host, int port) {
        return stubCache.computeIfAbsent(nodeId, id -> {
            ManagedChannel channel = channelCache.computeIfAbsent(nodeId, cid ->
                    ManagedChannelBuilder.forAddress(host, port)
                            .usePlaintext()
                            .keepAliveTime(30, TimeUnit.SECONDS)
                            .build()
            );
            log.info("Created forwarding channel to {} at {}:{}", nodeId, host, port);
            // Don't set deadline here - apply fresh deadline per request
            return LockServiceGrpc.newBlockingStub(channel);
        });
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down leader forwarder channels");
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
