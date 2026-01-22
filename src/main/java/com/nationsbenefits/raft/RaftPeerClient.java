package com.nationsbenefits.raft;

import com.nationsbenefits.raft.generated.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client implementation of RaftPeer for communicating with peer nodes.
 */
@Slf4j
public class RaftPeerClient implements RaftNode.RaftPeer, AutoCloseable {

    private final String nodeId;
    private final String host;
    private final int port;
    private final ManagedChannel channel;
    private final RaftServiceGrpc.RaftServiceBlockingStub blockingStub;

    public RaftPeerClient(String nodeId, String host, int port) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;

        log.info("Creating Raft peer client for {} at {}:{}", nodeId, host, port);

        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .build();

        this.blockingStub = RaftServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    @Override
    public RaftNode.VoteResponse requestVote(RaftNode.VoteRequest request) {
        try {
            var protoRequest = VoteRequest.newBuilder()
                    .setTerm(request.term())
                    .setCandidateId(request.candidateId())
                    .setLastLogIndex(request.lastLogIndex())
                    .setLastLogTerm(request.lastLogTerm())
                    .build();

            var response = RaftServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(2, TimeUnit.SECONDS)
                    .requestVote(protoRequest);

            return new RaftNode.VoteResponse(
                    response.getTerm(),
                    response.getVoteGranted(),
                    response.getVoterId()
            );
        } catch (StatusRuntimeException e) {
            log.warn("Failed to request vote from {}: {}", nodeId, e.getStatus());
            throw new RuntimeException("Vote request failed: " + e.getStatus(), e);
        }
    }

    @Override
    public RaftNode.AppendEntriesResponse appendEntries(RaftNode.AppendEntriesRequest request) {
        try {
            var protoRequest = AppendEntriesRequest.newBuilder()
                    .setTerm(request.term())
                    .setLeaderId(request.leaderId())
                    .setPrevLogIndex(request.prevLogIndex())
                    .setPrevLogTerm(request.prevLogTerm())
                    .setLeaderCommit(request.leaderCommit())
                    .addAllEntries(toProtoEntries(request.entries()))
                    .build();

            var response = RaftServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(2, TimeUnit.SECONDS)
                    .appendEntries(protoRequest);

            return new RaftNode.AppendEntriesResponse(
                    response.getTerm(),
                    response.getSuccess(),
                    response.getMatchIndex(),
                    response.getFollowerId()
            );
        } catch (StatusRuntimeException e) {
            log.warn("Failed to append entries to {}: {}", nodeId, e.getStatus());
            throw new RuntimeException("Append entries failed: " + e.getStatus(), e);
        }
    }

    private List<LogEntryProto> toProtoEntries(List<LogEntry> entries) {
        return entries.stream()
                .map(this::toProto)
                .toList();
    }

    private LogEntryProto toProto(LogEntry entry) {
        LogEntryTypeProto type = switch (entry.type()) {
            case NOOP -> LogEntryTypeProto.LOG_ENTRY_TYPE_NOOP;
            case ACQUIRE_LOCK -> LogEntryTypeProto.LOG_ENTRY_TYPE_ACQUIRE_LOCK;
            case RELEASE_LOCK -> LogEntryTypeProto.LOG_ENTRY_TYPE_RELEASE_LOCK;
            case EXTEND_LOCK -> LogEntryTypeProto.LOG_ENTRY_TYPE_EXTEND_LOCK;
        };

        return LogEntryProto.newBuilder()
                .setIndex(entry.index())
                .setTerm(entry.term())
                .setType(type)
                .setData(com.google.protobuf.ByteString.copyFrom(entry.data()))
                .build();
    }

    @Override
    public void close() {
        log.info("Shutting down Raft peer client for {}", nodeId);
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return "RaftPeerClient{nodeId='" + nodeId + "', address=" + host + ":" + port + "}";
    }
}
