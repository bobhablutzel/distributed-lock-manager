package com.nationsbenefits.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a distributed lock with fencing token support.
 * Immutable record for thread safety.
 */
public record Lock(
        String lockId,
        String holderId,
        String holderRegion,
        long fencingToken,
        Instant acquiredAt,
        Instant expiresAt
) {
    public Lock {
        Objects.requireNonNull(lockId, "lockId must not be null");
        Objects.requireNonNull(holderId, "holderId must not be null");
        Objects.requireNonNull(holderRegion, "holderRegion must not be null");
        Objects.requireNonNull(acquiredAt, "acquiredAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");

        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be non-negative");
        }
        if (!expiresAt.isAfter(acquiredAt)) {
            throw new IllegalArgumentException("expiresAt must be after acquiredAt");
        }
    }

    /**
     * Creates a new lock with the specified parameters.
     */
    public static Lock create(String lockId, String holderId, String holderRegion,
                              long fencingToken, long timeoutMs) {
        var now = Instant.now();
        return new Lock(
                lockId,
                holderId,
                holderRegion,
                fencingToken,
                now,
                now.plusMillis(timeoutMs)
        );
    }

    /**
     * Checks if this lock has expired.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Returns the remaining TTL in milliseconds.
     * Returns 0 if the lock has expired.
     */
    public long ttlMs() {
        var remaining = expiresAt.toEpochMilli() - Instant.now().toEpochMilli();
        return Math.max(0, remaining);
    }

    /**
     * Checks if this lock is held by the specified client and region.
     */
    public boolean isHeldBy(String clientId, String region) {
        return holderId.equals(clientId) && holderRegion.equals(region);
    }

    /**
     * Checks if the given fencing token matches this lock's token.
     */
    public boolean matchesToken(long token) {
        return this.fencingToken == token;
    }

    /**
     * Creates a new lock instance with an extended expiration time.
     */
    public Lock extend(long additionalMs) {
        return new Lock(
                lockId,
                holderId,
                holderRegion,
                fencingToken,
                acquiredAt,
                expiresAt.plusMillis(additionalMs)
        );
    }

    /**
     * Validates that the provided lockId is a valid UUID format.
     */
    public static boolean isValidLockId(String lockId) {
        if (lockId == null || lockId.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(lockId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
