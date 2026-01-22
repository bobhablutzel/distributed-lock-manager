package com.nationsbenefits.model;

import java.util.Objects;

/**
 * Represents a fencing token for distributed lock safety.
 * Fencing tokens are monotonically increasing values that help detect
 * stale lock holders and prevent split-brain scenarios.
 */
public record FencingToken(
        long value,
        String lockId,
        long issuedAt
) implements Comparable<FencingToken> {

    public FencingToken {
        Objects.requireNonNull(lockId, "lockId must not be null");
        if (value < 0) {
            throw new IllegalArgumentException("Token value must be non-negative");
        }
    }

    /**
     * Creates a new fencing token for the specified lock.
     */
    public static FencingToken create(String lockId, long value) {
        return new FencingToken(value, lockId, System.currentTimeMillis());
    }

    /**
     * Checks if this token is newer (higher) than another token.
     */
    public boolean isNewerThan(FencingToken other) {
        if (other == null) {
            return true;
        }
        return this.value > other.value;
    }

    /**
     * Checks if this token is valid for the given lock.
     */
    public boolean isValidFor(String lockId) {
        return this.lockId.equals(lockId);
    }

    @Override
    public int compareTo(FencingToken other) {
        return Long.compare(this.value, other.value);
    }

    /**
     * Returns the next token value.
     */
    public static long nextValue(long currentValue) {
        return currentValue + 1;
    }
}
