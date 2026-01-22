package com.nationsbenefits.grpc;

import com.nationsbenefits.config.RegionConfig;
import com.nationsbenefits.grpc.generated.*;
import com.nationsbenefits.quorum.QuorumManager;
import com.nationsbenefits.raft.RaftNode;
import com.nationsbenefits.service.LockStore;
import com.nationsbenefits.service.RegionLeaderForwarder;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * gRPC service implementation for cross-region communication.
 * Handles lock vote requests, notifications, and health checks from other regions.
 * Automatically forwards requests to the Raft leader if this node is a follower.
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class RegionGrpcService extends RegionServiceGrpc.RegionServiceImplBase {

    private final QuorumManager quorumManager;
    private final RaftNode raftNode;
    private final LockStore lockStore;
    private final RegionConfig regionConfig;
    private final RegionLeaderForwarder regionLeaderForwarder;

    @Override
    public void requestLockVote(LockVoteRequest request,
                                StreamObserver<LockVoteResponse> responseObserver) {
        log.debug("Received lock vote request from {} for lock {}",
                request.getRequestingRegion(), request.getLockId());

        // If not leader, forward to leader
        if (!raftNode.isLeader()) {
            var forwardedResponse = regionLeaderForwarder.forwardLockVoteRequest(request);
            if (forwardedResponse.isPresent() && forwardedResponse.get() != null) {
                log.debug("Forwarded lock vote request, got response: granted={}",
                        forwardedResponse.get().getGranted());
                responseObserver.onNext(forwardedResponse.get());
                responseObserver.onCompleted();
                return;
            }

            // Forwarding failed, deny the vote
            log.warn("Failed to forward lock vote request to leader");
            responseObserver.onNext(LockVoteResponse.newBuilder()
                    .setGranted(false)
                    .setRespondingRegion(regionConfig.getRegionId())
                    .setDenialReason("Not the regional leader and forwarding failed. Leader: " +
                            raftNode.getLeaderId().orElse("unknown"))
                    .build());
            responseObserver.onCompleted();
            return;
        }

        // This node is the leader, process the request
        var internalRequest = new QuorumManager.LockVoteRequest(
                request.getLockId(),
                request.getRequestingRegion(),
                request.getClientId(),
                request.getProposedTimeoutMs(),
                request.getProposedFencingToken(),
                request.getRequestTimestamp()
        );

        // Get vote decision from quorum manager
        var response = quorumManager.handleLockVoteRequest(internalRequest);

        // Convert to gRPC response
        var grpcResponse = LockVoteResponse.newBuilder()
                .setGranted(response.granted())
                .setRespondingRegion(response.respondingRegion());

        if (!response.granted()) {
            if (response.currentHolderRegion() != null) {
                grpcResponse.setCurrentHolderRegion(response.currentHolderRegion());
            }
            if (response.currentHolderClient() != null) {
                grpcResponse.setCurrentHolderClient(response.currentHolderClient());
            }
            grpcResponse.setCurrentFencingToken(response.currentFencingToken());
            grpcResponse.setCurrentExpiresAt(response.currentExpiresAt());
            if (response.denialReason() != null) {
                grpcResponse.setDenialReason(response.denialReason());
            }
        }

        responseObserver.onNext(grpcResponse.build());
        responseObserver.onCompleted();
    }

    @Override
    public void notifyLockAcquired(LockAcquiredNotification request,
                                   StreamObserver<NotificationAck> responseObserver) {
        log.debug("Received lock acquired notification from {} for lock {}",
                request.getNotifyingRegion(), request.getLockId());

        // If not leader, forward to leader (leader manages pending votes)
        if (!raftNode.isLeader()) {
            var forwardedResponse = regionLeaderForwarder.forwardLockAcquiredNotification(request);
            if (forwardedResponse.isPresent() && forwardedResponse.get() != null) {
                responseObserver.onNext(forwardedResponse.get());
                responseObserver.onCompleted();
                return;
            }
            // Forwarding failed, but still process locally
            log.warn("Failed to forward lock acquired notification to leader, processing locally");
        }

        try {
            // Convert to internal notification format
            var internalNotification = new QuorumManager.LockAcquiredNotification(
                    request.getLockId(),
                    request.getHolderRegion(),
                    request.getHolderClient(),
                    request.getFencingToken(),
                    request.getExpiresAt(),
                    request.getNotifyingRegion()
            );

            // Handle the notification
            quorumManager.handleLockAcquiredNotification(internalNotification);

            responseObserver.onNext(NotificationAck.newBuilder()
                    .setSuccess(true)
                    .setRegionId(regionConfig.getRegionId())
                    .build());
        } catch (Exception e) {
            log.error("Error handling lock acquired notification: {}", e.getMessage());
            responseObserver.onNext(NotificationAck.newBuilder()
                    .setSuccess(false)
                    .setRegionId(regionConfig.getRegionId())
                    .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void notifyLockReleased(LockReleasedNotification request,
                                   StreamObserver<NotificationAck> responseObserver) {
        log.debug("Received lock released notification from {} for lock {}",
                request.getNotifyingRegion(), request.getLockId());

        // If not leader, forward to leader (leader manages pending votes)
        if (!raftNode.isLeader()) {
            var forwardedResponse = regionLeaderForwarder.forwardLockReleasedNotification(request);
            if (forwardedResponse.isPresent() && forwardedResponse.get() != null) {
                responseObserver.onNext(forwardedResponse.get());
                responseObserver.onCompleted();
                return;
            }
            // Forwarding failed, but still process locally
            log.warn("Failed to forward lock released notification to leader, processing locally");
        }

        try {
            // Convert to internal notification format
            var internalNotification = new QuorumManager.LockReleasedNotification(
                    request.getLockId(),
                    request.getFencingToken(),
                    request.getNotifyingRegion()
            );

            // Handle the notification
            quorumManager.handleLockReleasedNotification(internalNotification);

            responseObserver.onNext(NotificationAck.newBuilder()
                    .setSuccess(true)
                    .setRegionId(regionConfig.getRegionId())
                    .build());
        } catch (Exception e) {
            log.error("Error handling lock released notification: {}", e.getMessage());
            responseObserver.onNext(NotificationAck.newBuilder()
                    .setSuccess(false)
                    .setRegionId(regionConfig.getRegionId())
                    .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void ping(PingRequest request,
                     StreamObserver<PingResponse> responseObserver) {
        log.debug("Received ping from region {}", request.getRegionId());

        responseObserver.onNext(PingResponse.newBuilder()
                .setRegionId(regionConfig.getRegionId())
                .setIsLeader(raftNode.isLeader())
                .setTimestamp(System.currentTimeMillis())
                .setHealthy(true)
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void syncLockState(SyncLockStateRequest request,
                              StreamObserver<SyncLockStateResponse> responseObserver) {
        log.debug("Received sync lock state request from {}", request.getRequestingRegion());

        var responseBuilder = SyncLockStateResponse.newBuilder()
                .setRegionId(regionConfig.getRegionId());

        // Get all locks from the lock store
        var allLocks = lockStore.getAllActiveLocks();

        for (var lock : allLocks) {
            // Filter by requested lock IDs if specified
            if (!request.getLockIdsList().isEmpty() &&
                    !request.getLockIdsList().contains(lock.lockId())) {
                continue;
            }

            responseBuilder.addLocks(LockState.newBuilder()
                    .setLockId(lock.lockId())
                    .setHolderRegion(lock.holderRegion() != null ? lock.holderRegion() : "")
                    .setHolderClient(lock.holderId())
                    .setFencingToken(lock.fencingToken())
                    .setExpiresAt(lock.expiresAt().toEpochMilli())
                    .setAcquiredAt(lock.acquiredAt().toEpochMilli())
                    .build());
        }

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
}
