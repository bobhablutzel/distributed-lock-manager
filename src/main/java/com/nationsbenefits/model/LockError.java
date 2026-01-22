package com.nationsbenefits.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Represents an error that occurred during a lock operation.
 */
public record LockError(
        LockStatus status,
        String message,
        Optional<String> currentHolderId,
        Optional<Long> currentFencingToken
) {
    public LockError {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(currentHolderId, "currentHolderId optional must not be null");
        Objects.requireNonNull(currentFencingToken, "currentFencingToken optional must not be null");
    }

    /**
     * Creates an error with just status and message.
     */
    public LockError(LockStatus status, String message) {
        this(status, message, Optional.empty(), Optional.empty());
    }

    /**
     * Creates an error for a lock already held by another client.
     */
    public static LockError alreadyLocked(String holderId, long fencingToken) {
        return new LockError(
                LockStatus.ALREADY_LOCKED,
                "Lock is already held by: " + holderId,
                Optional.of(holderId),
                Optional.of(fencingToken)
        );
    }

    /**
     * Creates an error for an invalid fencing token.
     */
    public static LockError invalidToken(long expectedToken, long providedToken) {
        return new LockError(
                LockStatus.INVALID_TOKEN,
                String.format("Invalid fencing token. Expected: %d, Provided: %d",
                        expectedToken, providedToken)
        );
    }

    /**
     * Creates an error for a lock not found.
     */
    public static LockError notFound(String lockId) {
        return new LockError(
                LockStatus.NOT_FOUND,
                "Lock not found: " + lockId
        );
    }

    /**
     * Creates an error for an expired lock.
     */
    public static LockError expired(String lockId) {
        return new LockError(
                LockStatus.EXPIRED,
                "Lock has expired: " + lockId
        );
    }

    /**
     * Creates an error for quorum failure.
     */
    public static LockError quorumFailed(int votesReceived, int votesRequired) {
        return new LockError(
                LockStatus.QUORUM_FAILED,
                String.format("Quorum not reached. Received: %d, Required: %d",
                        votesReceived, votesRequired)
        );
    }

    /**
     * Creates an error for timeout.
     */
    public static LockError timeout(String operation) {
        return new LockError(
                LockStatus.TIMEOUT,
                "Operation timed out: " + operation
        );
    }

    /**
     * Creates an error for not being the leader.
     */
    public static LockError notLeader(String leaderId) {
        return new LockError(
                LockStatus.NOT_LEADER,
                "This node is not the leader. Current leader: " + leaderId
        );
    }

    /**
     * Creates a generic error.
     */
    public static LockError error(String message) {
        return new LockError(
                LockStatus.ERROR,
                message
        );
    }
}
