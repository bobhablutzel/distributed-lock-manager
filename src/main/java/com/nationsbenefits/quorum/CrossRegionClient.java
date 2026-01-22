package com.nationsbenefits.quorum;

import com.nationsbenefits.grpc.generated.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * gRPC client for cross-region communication.
 * Handles communication with other regional leaders for quorum voting.
 */
@Slf4j
@Getter
public class CrossRegionClient {
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    private final String host;
    private final int port;
    private final String regionId;
    private final ManagedChannel channel;
    private final RegionServiceGrpc.RegionServiceBlockingStub stub;

    public CrossRegionClient(String host, int port, String regionId) {
        this.host = host;
        this.port = port;
        this.regionId = regionId;

        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext() // Use TLS in production
                .keepAliveTime(30, TimeUnit.SECONDS)
                .build();

        this.stub = RegionServiceGrpc.newBlockingStub(channel);

        log.info("Created cross-region client for {} at {}:{}", regionId, host, port);
    }

    /**
     * Requests a lock vote from the remote region.
     */
    public QuorumManager.LockVoteResponse requestLockVote(QuorumManager.LockVoteRequest request) {
        log.debug("Requesting lock vote from region {} for lock {}",
                regionId, request.lockId());

        try {
            var grpcRequest = LockVoteRequest.newBuilder()
                    .setLockId(request.lockId())
                    .setRequestingRegion(request.requestingRegion())
                    .setClientId(request.clientId())
                    .setProposedTimeoutMs(request.proposedTimeoutMs())
                    .setProposedFencingToken(request.proposedFencingToken())
                    .setRequestTimestamp(request.requestTimestamp())
                    .build();

            var grpcResponse = stub
                    .withDeadlineAfter(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .requestLockVote(grpcRequest);

            return new QuorumManager.LockVoteResponse(
                    grpcResponse.getGranted(),
                    grpcResponse.getCurrentHolderRegion(),
                    grpcResponse.getCurrentHolderClient(),
                    grpcResponse.getCurrentFencingToken(),
                    grpcResponse.getCurrentExpiresAt(),
                    grpcResponse.getRespondingRegion(),
                    grpcResponse.getDenialReason()
            );
        } catch (StatusRuntimeException e) {
            log.error("gRPC error requesting vote from {}: {}", regionId, e.getStatus());
            throw new RuntimeException("Failed to request vote from " + regionId, e);
        }
    }

    /**
     * Notifies the remote region that a lock has been acquired.
     */
    public void notifyLockAcquired(QuorumManager.LockAcquiredNotification notification) {
        log.debug("Notifying region {} of lock acquisition: {}",
                regionId, notification.lockId());

        try {
            var grpcNotification = LockAcquiredNotification.newBuilder()
                    .setLockId(notification.lockId())
                    .setHolderRegion(notification.holderRegion())
                    .setHolderClient(notification.holderId())
                    .setFencingToken(notification.fencingToken())
                    .setExpiresAt(notification.expiresAt())
                    .setNotifyingRegion(notification.notifyingRegion())
                    .build();

            stub.withDeadlineAfter(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .notifyLockAcquired(grpcNotification);
        } catch (StatusRuntimeException e) {
            log.error("gRPC error notifying {} of acquisition: {}", regionId, e.getStatus());
            throw new RuntimeException("Failed to notify " + regionId, e);
        }
    }

    /**
     * Notifies the remote region that a lock has been released.
     */
    public void notifyLockReleased(QuorumManager.LockReleasedNotification notification) {
        log.debug("Notifying region {} of lock release: {}",
                regionId, notification.lockId());

        try {
            var grpcNotification = LockReleasedNotification.newBuilder()
                    .setLockId(notification.lockId())
                    .setFencingToken(notification.fencingToken())
                    .setNotifyingRegion(notification.notifyingRegion())
                    .build();

            stub.withDeadlineAfter(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .notifyLockReleased(grpcNotification);
        } catch (StatusRuntimeException e) {
            log.error("gRPC error notifying {} of release: {}", regionId, e.getStatus());
            throw new RuntimeException("Failed to notify " + regionId, e);
        }
    }

    /**
     * Pings the remote region to check health.
     */
    public boolean ping(String localRegionId) {
        try {
            var request = PingRequest.newBuilder()
                    .setRegionId(localRegionId)
                    .setTimestamp(System.currentTimeMillis())
                    .build();

            var response = stub
                    .withDeadlineAfter(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .ping(request);

            return response.getHealthy();
        } catch (Exception e) {
            log.warn("Ping to region {} failed: {}", regionId, e.getMessage());
            return false;
        }
    }

    /**
     * Shuts down the channel.
     */
    public void shutdown() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
