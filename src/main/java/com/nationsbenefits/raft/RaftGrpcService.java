package com.nationsbenefits.raft;

import com.nationsbenefits.raft.generated.*;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.List;

/**
 * gRPC service for handling Raft consensus RPCs from peer nodes.
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class RaftGrpcService extends RaftServiceGrpc.RaftServiceImplBase {

    private final RaftNode raftNode;

    @Override
    public void requestVote(VoteRequest request, StreamObserver<VoteResponse> responseObserver) {
        log.debug("Received vote request from {} for term {}", request.getCandidateId(), request.getTerm());

        var internalRequest = new RaftNode.VoteRequest(
                request.getTerm(),
                request.getCandidateId(),
                request.getLastLogIndex(),
                request.getLastLogTerm()
        );

        var internalResponse = raftNode.handleVoteRequest(internalRequest);

        var response = VoteResponse.newBuilder()
                .setTerm(internalResponse.term())
                .setVoteGranted(internalResponse.voteGranted())
                .setVoterId(internalResponse.voterId())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void appendEntries(AppendEntriesRequest request, StreamObserver<AppendEntriesResponse> responseObserver) {
        log.debug("Received append entries from {} for term {}, {} entries",
                request.getLeaderId(), request.getTerm(), request.getEntriesCount());

        List<LogEntry> entries = request.getEntriesList().stream()
                .map(this::fromProto)
                .toList();

        var internalRequest = new RaftNode.AppendEntriesRequest(
                request.getTerm(),
                request.getLeaderId(),
                request.getPrevLogIndex(),
                request.getPrevLogTerm(),
                entries,
                request.getLeaderCommit()
        );

        var internalResponse = raftNode.handleAppendEntries(internalRequest);

        var response = AppendEntriesResponse.newBuilder()
                .setTerm(internalResponse.term())
                .setSuccess(internalResponse.success())
                .setMatchIndex(internalResponse.matchIndex())
                .setFollowerId(internalResponse.followerId())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private LogEntry fromProto(LogEntryProto proto) {
        LogEntryType type = switch (proto.getType()) {
            case LOG_ENTRY_TYPE_NOOP -> LogEntryType.NOOP;
            case LOG_ENTRY_TYPE_ACQUIRE_LOCK -> LogEntryType.ACQUIRE_LOCK;
            case LOG_ENTRY_TYPE_RELEASE_LOCK -> LogEntryType.RELEASE_LOCK;
            case LOG_ENTRY_TYPE_EXTEND_LOCK -> LogEntryType.EXTEND_LOCK;
            default -> LogEntryType.NOOP;
        };

        return new LogEntry(
                proto.getIndex(),
                proto.getTerm(),
                type,
                proto.getData().toByteArray()
        );
    }
}
