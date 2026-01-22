package com.nationsbenefits.grpc;

import com.nationsbenefits.grpc.generated.*;
import com.nationsbenefits.model.LockStatus;
import com.nationsbenefits.raft.RaftNode;
import com.nationsbenefits.service.LeaderForwarder;
import com.nationsbenefits.service.LockService;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * gRPC service implementation for client-facing lock operations.
 * Automatically forwards requests to the Raft leader if this node is a follower.
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class LockGrpcService extends LockServiceGrpc.LockServiceImplBase {

    private final LockService lockService;
    private final RaftNode raftNode;
    private final LeaderForwarder leaderForwarder;

    // Metadata key to detect forwarded requests and prevent loops
    private static final Metadata.Key<String> FORWARDED_KEY =
            Metadata.Key.of("x-forwarded-from", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public void acquireLock(AcquireLockRequest request,
                            StreamObserver<AcquireLockResponse> responseObserver) {
        log.debug("gRPC AcquireLock: lockId={}, clientId={}, timeout={}",
                request.getLockId(), request.getClientId(), request.getTimeoutMs());

        // If not leader, forward to leader
        if (!raftNode.isLeader()) {
            var forwardedResponse = leaderForwarder.forwardAcquireLock(request);
            if (forwardedResponse.isPresent() && forwardedResponse.get() != null) {
                log.debug("Forwarded acquire lock request, got response: {}",
                        forwardedResponse.get().getStatus());
                responseObserver.onNext(forwardedResponse.get());
                responseObserver.onCompleted();
                return;
            }

            // Forwarding failed, return not leader error
            log.warn("Failed to forward acquire lock request to leader");
            responseObserver.onNext(AcquireLockResponse.newBuilder()
                    .setSuccess(false)
                    .setStatus(com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_NOT_LEADER)
                    .setErrorMessage("Not the leader and forwarding failed. Leader: " +
                            raftNode.getLeaderId().orElse("unknown"))
                    .build());
            responseObserver.onCompleted();
            return;
        }

        // This node is the leader, process the request
        lockService.acquireLock(
                request.getLockId(),
                request.getClientId(),
                request.getTimeoutMs()
        ).whenComplete((result, error) -> {
            if (error != null) {
                log.error("Error acquiring lock: {}", error.getMessage());
                responseObserver.onNext(AcquireLockResponse.newBuilder()
                        .setSuccess(false)
                        .setStatus(com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_ERROR)
                        .setErrorMessage(error.getMessage())
                        .build());
                responseObserver.onCompleted();
                return;
            }

            var responseBuilder = AcquireLockResponse.newBuilder();

            if (result.isSuccess()) {
                var lock = result.getValue();
                responseBuilder
                        .setSuccess(true)
                        .setFencingToken(lock.fencingToken())
                        .setExpiresAt(lock.expiresAt().toEpochMilli())
                        .setStatus(com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_OK);
            } else {
                var lockError = result.getError();
                responseBuilder
                        .setSuccess(false)
                        .setErrorMessage(lockError.message())
                        .setStatus(mapStatus(lockError.status()));
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        });
    }

    @Override
    public void releaseLock(ReleaseLockRequest request,
                            StreamObserver<ReleaseLockResponse> responseObserver) {
        log.debug("gRPC ReleaseLock: lockId={}, clientId={}, token={}",
                request.getLockId(), request.getClientId(), request.getFencingToken());

        // If not leader, forward to leader
        if (!raftNode.isLeader()) {
            var forwardedResponse = leaderForwarder.forwardReleaseLock(request);
            if (forwardedResponse.isPresent() && forwardedResponse.get() != null) {
                log.debug("Forwarded release lock request, got response: {}",
                        forwardedResponse.get().getStatus());
                responseObserver.onNext(forwardedResponse.get());
                responseObserver.onCompleted();
                return;
            }

            // Forwarding failed, return not leader error
            log.warn("Failed to forward release lock request to leader");
            responseObserver.onNext(ReleaseLockResponse.newBuilder()
                    .setSuccess(false)
                    .setStatus(com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_NOT_LEADER)
                    .setErrorMessage("Not the leader and forwarding failed. Leader: " +
                            raftNode.getLeaderId().orElse("unknown"))
                    .build());
            responseObserver.onCompleted();
            return;
        }

        // This node is the leader, process the request
        lockService.releaseLock(
                request.getLockId(),
                request.getClientId(),
                request.getFencingToken()
        ).whenComplete((result, error) -> {
            if (error != null) {
                log.error("Error releasing lock: {}", error.getMessage());
                responseObserver.onNext(ReleaseLockResponse.newBuilder()
                        .setSuccess(false)
                        .setStatus(com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_ERROR)
                        .setErrorMessage(error.getMessage())
                        .build());
                responseObserver.onCompleted();
                return;
            }

            var responseBuilder = ReleaseLockResponse.newBuilder();

            if (result.isSuccess()) {
                responseBuilder
                        .setSuccess(true)
                        .setStatus(com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_OK);
            } else {
                var lockError = result.getError();
                responseBuilder
                        .setSuccess(false)
                        .setErrorMessage(lockError.message())
                        .setStatus(mapStatus(lockError.status()));
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        });
    }

    @Override
    public void checkLock(CheckLockRequest request,
                          StreamObserver<CheckLockResponse> responseObserver) {
        log.debug("gRPC CheckLock: lockId={}", request.getLockId());

        // CheckLock can be served by any node (read from local state)
        var result = lockService.checkLock(request.getLockId());

        var responseBuilder = CheckLockResponse.newBuilder();

        if (result.isSuccess()) {
            var info = result.getValue();
            responseBuilder
                    .setIsLocked(info.isLocked())
                    .setHolderId(info.holderId() != null ? info.holderId() : "")
                    .setFencingToken(info.fencingToken())
                    .setTtlMs(info.ttlMs())
                    .setExpiresAt(info.expiresAt())
                    .setStatus(com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_OK);
        } else {
            responseBuilder
                    .setStatus(mapStatus(result.getError().status()));
        }

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    private com.nationsbenefits.grpc.generated.LockStatus mapStatus(LockStatus status) {
        return switch (status) {
            case OK -> com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_OK;
            case ALREADY_LOCKED -> com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_ALREADY_LOCKED;
            case NOT_FOUND -> com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_NOT_FOUND;
            case INVALID_TOKEN -> com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_INVALID_TOKEN;
            case EXPIRED -> com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_EXPIRED;
            case QUORUM_FAILED -> com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_QUORUM_FAILED;
            case ERROR -> com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_ERROR;
            case TIMEOUT -> com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_TIMEOUT;
            case NOT_LEADER -> com.nationsbenefits.grpc.generated.LockStatus.LOCK_STATUS_NOT_LEADER;
        };
    }
}
