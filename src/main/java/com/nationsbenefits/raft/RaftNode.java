package com.nationsbenefits.raft;

import com.nationsbenefits.config.RaftConfig;
import com.nationsbenefits.model.LockResult;
import com.nationsbenefits.service.FencingTokenGenerator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Raft consensus node implementation.
 * Handles leader election, log replication, and state machine application.
 */
@Slf4j
@Component
public class RaftNode {

    private final RaftConfig config;
    private final RaftLog raftLog;
    private final RaftStateMachine stateMachine;
    private final FencingTokenGenerator tokenGenerator;

    // Persistent state
    private final AtomicLong currentTerm = new AtomicLong(0);
    private final AtomicReference<String> votedFor = new AtomicReference<>(null);

    // Volatile state
    private volatile RaftState state = RaftState.FOLLOWER;
    private volatile String leaderId = null;
    private volatile long commitIndex = 0;
    private volatile Instant lastHeartbeat = Instant.now();

    // Leader state
    private final Map<String, Long> nextIndex = new ConcurrentHashMap<>();
    private final Map<String, Long> matchIndex = new ConcurrentHashMap<>();

    // Pending operations waiting for commit
    private final Map<Long, CompletableFuture<LockResult<?>>> pendingOperations = new ConcurrentHashMap<>();
    private final Map<Long, Consumer<LockResult<?>>> pendingCallbacks = new ConcurrentHashMap<>();

    // Thread management
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> electionTimer;
    private ScheduledFuture<?> heartbeatTimer;
    private final ReentrantLock stateLock = new ReentrantLock();

    // Cluster peers (gRPC client stubs will be injected)
    private final List<RaftPeer> peers = new CopyOnWriteArrayList<>();

    public RaftNode(RaftConfig config, RaftLog raftLog, RaftStateMachine stateMachine,
                    FencingTokenGenerator tokenGenerator) {
        this.config = config;
        this.raftLog = raftLog;
        this.stateMachine = stateMachine;
        this.tokenGenerator = tokenGenerator;
    }

    @PostConstruct
    public void init() {
        scheduler = Executors.newScheduledThreadPool(2,
                Thread.ofVirtual().name("raft-", 0).factory()
        );
        log.info("Raft node {} initialized as FOLLOWER (waiting for cluster setup)", config.getNodeId());
    }

    /**
     * Starts the Raft election process.
     * Called by RaftClusterInitializer after peers are connected.
     */
    public void startElectionProcess() {
        resetElectionTimer();
        log.info("Raft node {} election process started with {} peer(s)", config.getNodeId(), peers.size());
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("Raft node {} stopped", config.getNodeId());
    }

    /**
     * Adds a peer to the cluster.
     */
    public void addPeer(RaftPeer peer) {
        peers.add(peer);
        nextIndex.put(peer.nodeId(), raftLog.getLastIndex() + 1);
        matchIndex.put(peer.nodeId(), 0L);
    }

    /**
     * Submits a command to the Raft log.
     * Returns a future that completes when the command is committed and applied.
     */
    public CompletableFuture<LockResult<?>> submit(LogEntryType type, LockCommand command) {
        stateLock.lock();
        try {
            if (state != RaftState.LEADER) {
                return CompletableFuture.completedFuture(
                        LockResult.failure(
                                com.nationsbenefits.model.LockStatus.NOT_LEADER,
                                "Not the leader. Current leader: " + leaderId
                        )
                );
            }

            long term = currentTerm.get();
            long index = raftLog.getLastIndex() + 1;
            var entry = new LogEntry(index, term, type, command.serialize());

            raftLog.append(entry);
            log.debug("Leader appended entry at index {} term {}", index, term);

            var future = new CompletableFuture<LockResult<?>>();
            pendingOperations.put(index, future);

            // Trigger immediate replication
            replicateToFollowers();

            return future;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Handles a vote request from a candidate.
     */
    public VoteResponse handleVoteRequest(VoteRequest request) {
        stateLock.lock();
        try {
            long term = currentTerm.get();

            // If request term is higher, step down
            if (request.term() > term) {
                stepDown(request.term());
                term = request.term();
            }

            // Deny if term is old
            if (request.term() < term) {
                return new VoteResponse(term, false, config.getNodeId());
            }

            // Check if we can vote for this candidate
            String voted = votedFor.get();
            boolean canVote = (voted == null || voted.equals(request.candidateId()));

            // Check if candidate's log is at least as up-to-date
            boolean logOk = request.lastLogTerm() > raftLog.getLastTerm() ||
                    (request.lastLogTerm() == raftLog.getLastTerm() &&
                            request.lastLogIndex() >= raftLog.getLastIndex());

            if (canVote && logOk) {
                votedFor.set(request.candidateId());
                resetElectionTimer();
                log.info("Voting for {} in term {}", request.candidateId(), term);
                return new VoteResponse(term, true, config.getNodeId());
            }

            return new VoteResponse(term, false, config.getNodeId());
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Handles an append entries request from leader.
     */
    public AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        stateLock.lock();
        try {
            long term = currentTerm.get();

            // If request term is higher, step down
            if (request.term() > term) {
                stepDown(request.term());
                term = request.term();
            }

            // Deny if term is old
            if (request.term() < term) {
                return new AppendEntriesResponse(term, false, 0, config.getNodeId());
            }

            // Valid leader, reset election timer
            resetElectionTimer();
            leaderId = request.leaderId();
            state = RaftState.FOLLOWER;

            // Check log consistency
            if (!raftLog.containsEntry(request.prevLogIndex(), request.prevLogTerm())) {
                log.debug("Log inconsistency at index {}", request.prevLogIndex());
                return new AppendEntriesResponse(term, false,
                        raftLog.getLastIndex(), config.getNodeId());
            }

            // Append new entries
            if (!request.entries().isEmpty()) {
                // Remove conflicting entries
                for (var entry : request.entries()) {
                    var existing = raftLog.get(entry.index());
                    if (existing.isPresent() && existing.get().term() != entry.term()) {
                        raftLog.truncateFrom(entry.index());
                        break;
                    }
                }

                // Append entries not in log
                for (var entry : request.entries()) {
                    if (entry.index() > raftLog.getLastIndex()) {
                        raftLog.append(entry);
                    }
                }
            }

            // Update commit index
            if (request.leaderCommit() > commitIndex) {
                commitIndex = Math.min(request.leaderCommit(), raftLog.getLastIndex());
                applyCommittedEntries();
            }

            return new AppendEntriesResponse(term, true,
                    raftLog.getLastIndex(), config.getNodeId());
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Checks if this node is the leader.
     */
    public boolean isLeader() {
        return state == RaftState.LEADER;
    }

    /**
     * Gets the current leader ID.
     */
    public Optional<String> getLeaderId() {
        return Optional.ofNullable(leaderId);
    }

    /**
     * Gets the current state.
     */
    public RaftState getState() {
        return state;
    }

    /**
     * Gets the current term.
     */
    public long getCurrentTerm() {
        return currentTerm.get();
    }

    private void resetElectionTimer() {
        if (electionTimer != null) {
            electionTimer.cancel(false);
        }

        long timeout = config.getElectionTimeoutMs() +
                ThreadLocalRandom.current().nextLong(config.getElectionTimeoutMs());

        electionTimer = scheduler.schedule(
                this::startElection,
                timeout,
                TimeUnit.MILLISECONDS
        );
        lastHeartbeat = Instant.now();
    }

    private void startElection() {
        stateLock.lock();
        try {
            if (state == RaftState.LEADER) {
                return;
            }

            state = RaftState.CANDIDATE;
            long term = currentTerm.incrementAndGet();
            votedFor.set(config.getNodeId());
            leaderId = null;

            log.info("Starting election for term {}", term);

            // Count votes (including self-vote)
            int votesNeeded = (peers.size() + 1) / 2 + 1;
            var votesReceived = new AtomicLong(1);

            // Check if self-vote is sufficient (single node cluster)
            if (votesReceived.get() >= votesNeeded) {
                becomeLeader();
                return;
            }

            // Request votes from all peers
            var request = new VoteRequest(
                    term,
                    config.getNodeId(),
                    raftLog.getLastIndex(),
                    raftLog.getLastTerm()
            );

            for (var peer : peers) {
                CompletableFuture.runAsync(() -> {
                    try {
                        var response = peer.requestVote(request);
                        handleVoteResponse(response, term, votesReceived, votesNeeded);
                    } catch (Exception e) {
                        log.warn("Failed to request vote from {}: {}", peer.nodeId(), e.getMessage());
                    }
                }, scheduler);
            }

            // Set election timeout for next round
            resetElectionTimer();
        } finally {
            stateLock.unlock();
        }
    }

    private void handleVoteResponse(VoteResponse response, long electionTerm,
                                    AtomicLong votesReceived, int votesNeeded) {
        stateLock.lock();
        try {
            // Ignore stale responses
            if (currentTerm.get() != electionTerm || state != RaftState.CANDIDATE) {
                return;
            }

            // Step down if higher term discovered
            if (response.term() > currentTerm.get()) {
                stepDown(response.term());
                return;
            }

            if (response.voteGranted()) {
                long votes = votesReceived.incrementAndGet();
                log.debug("Received vote from {}, total: {}/{}",
                        response.voterId(), votes, votesNeeded);

                if (votes >= votesNeeded && state == RaftState.CANDIDATE) {
                    becomeLeader();
                }
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void becomeLeader() {
        state = RaftState.LEADER;
        leaderId = config.getNodeId();

        log.info("Became leader for term {}", currentTerm.get());

        // Initialize leader state
        long lastIndex = raftLog.getLastIndex();
        for (var peer : peers) {
            nextIndex.put(peer.nodeId(), lastIndex + 1);
            matchIndex.put(peer.nodeId(), 0L);
        }

        // Append no-op entry to commit previous term's entries
        var noopEntry = LogEntry.noop(lastIndex + 1, currentTerm.get());
        raftLog.append(noopEntry);

        // Start heartbeat timer
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel(false);
        }
        heartbeatTimer = scheduler.scheduleAtFixedRate(
                this::sendHeartbeats,
                0,
                config.getHeartbeatIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        // Cancel election timer
        if (electionTimer != null) {
            electionTimer.cancel(false);
        }
    }

    private void stepDown(long newTerm) {
        currentTerm.set(newTerm);
        state = RaftState.FOLLOWER;
        votedFor.set(null);

        if (heartbeatTimer != null) {
            heartbeatTimer.cancel(false);
            heartbeatTimer = null;
        }

        resetElectionTimer();
        log.info("Stepped down to follower, term {}", newTerm);
    }

    private void sendHeartbeats() {
        if (state != RaftState.LEADER) {
            return;
        }

        replicateToFollowers();
    }

    private void replicateToFollowers() {
        long term = currentTerm.get();
        long currentCommit = commitIndex;

        // Single-node cluster: immediately commit since we are the only voter
        if (peers.isEmpty()) {
            updateCommitIndex();
            return;
        }

        for (var peer : peers) {
            CompletableFuture.runAsync(() -> {
                try {
                    replicateToPeer(peer, term, currentCommit);
                } catch (Exception e) {
                    log.warn("Failed to replicate to {}: {}", peer.nodeId(), e.getMessage());
                }
            }, scheduler);
        }
    }

    private void replicateToPeer(RaftPeer peer, long term, long leaderCommit) {
        long prevIndex = nextIndex.getOrDefault(peer.nodeId(), 1L) - 1;
        long prevTerm = raftLog.getTermAt(prevIndex);
        var entries = raftLog.getFrom(prevIndex + 1);

        var request = new AppendEntriesRequest(
                term,
                config.getNodeId(),
                prevIndex,
                prevTerm,
                entries,
                leaderCommit
        );

        var response = peer.appendEntries(request);

        stateLock.lock();
        try {
            if (currentTerm.get() != term || state != RaftState.LEADER) {
                return;
            }

            if (response.term() > currentTerm.get()) {
                stepDown(response.term());
                return;
            }

            if (response.success()) {
                long newMatchIndex = response.matchIndex();
                matchIndex.put(peer.nodeId(), newMatchIndex);
                nextIndex.put(peer.nodeId(), newMatchIndex + 1);

                // Check if we can advance commit index
                updateCommitIndex();
            } else {
                // Decrement next index and retry
                long next = nextIndex.getOrDefault(peer.nodeId(), 1L);
                nextIndex.put(peer.nodeId(), Math.max(1, next - 1));
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void updateCommitIndex() {
        // Find the highest index replicated to a majority
        List<Long> allMatchIndices = new ArrayList<>();
        allMatchIndices.add(raftLog.getLastIndex()); // Leader's own index
        allMatchIndices.addAll(matchIndex.values());
        Collections.sort(allMatchIndices);

        int majorityIndex = allMatchIndices.size() / 2;
        long newCommitIndex = allMatchIndices.get(majorityIndex);

        // Only commit entries from current term
        if (newCommitIndex > commitIndex &&
                raftLog.getTermAt(newCommitIndex) == currentTerm.get()) {
            commitIndex = newCommitIndex;
            applyCommittedEntries();
        }
    }

    private void applyCommittedEntries() {
        long lastApplied = stateMachine.getLastAppliedIndex();

        for (long i = lastApplied + 1; i <= commitIndex; i++) {
            var entry = raftLog.get(i);
            if (entry.isPresent()) {
                long index = i;
                stateMachine.apply(entry.get(), result -> {
                    var future = pendingOperations.remove(index);
                    if (future != null) {
                        future.complete(result);
                    }
                    var callback = pendingCallbacks.remove(index);
                    if (callback != null) {
                        callback.accept(result);
                    }
                });
            }
        }
    }

    // Record types for internal communication
    public record VoteRequest(long term, String candidateId, long lastLogIndex, long lastLogTerm) {}
    public record VoteResponse(long term, boolean voteGranted, String voterId) {}
    public record AppendEntriesRequest(long term, String leaderId, long prevLogIndex,
                                        long prevLogTerm, List<LogEntry> entries, long leaderCommit) {}
    public record AppendEntriesResponse(long term, boolean success, long matchIndex, String followerId) {}

    /**
     * Interface for communicating with Raft peers.
     */
    public interface RaftPeer {
        String nodeId();
        VoteResponse requestVote(VoteRequest request);
        AppendEntriesResponse appendEntries(AppendEntriesRequest request);
    }
}
