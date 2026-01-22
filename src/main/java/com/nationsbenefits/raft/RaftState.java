package com.nationsbenefits.raft;

/**
 * Represents the state of a Raft node.
 */
public enum RaftState {
    /**
     * Node is a follower, accepting entries from leader.
     */
    FOLLOWER,

    /**
     * Node is a candidate, running for leader election.
     */
    CANDIDATE,

    /**
     * Node is the leader, accepting client requests.
     */
    LEADER
}
