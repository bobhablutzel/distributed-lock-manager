package com.nationsbenefits.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates monotonically increasing fencing tokens for distributed locks.
 * Each lock has its own token sequence to ensure uniqueness.
 */
@Slf4j
@Component
public class FencingTokenGenerator {

    private final Map<String, AtomicLong> tokenSequences = new ConcurrentHashMap<>();
    private final AtomicLong globalSequence = new AtomicLong(0);

    /**
     * Gets the next fencing token for a specific lock.
     * Tokens are monotonically increasing per lock.
     *
     * @param lockId The lock identifier
     * @return The next fencing token
     */
    public long nextToken(String lockId) {
        return tokenSequences
                .computeIfAbsent(lockId, k -> new AtomicLong(0))
                .incrementAndGet();
    }

    /**
     * Gets the current token for a lock without incrementing.
     *
     * @param lockId The lock identifier
     * @return The current token value, or 0 if no token exists
     */
    public long currentToken(String lockId) {
        var sequence = tokenSequences.get(lockId);
        return sequence != null ? sequence.get() : 0;
    }

    /**
     * Initializes or updates the token sequence for a lock.
     * Used when syncing state from other regions.
     *
     * @param lockId The lock identifier
     * @param token  The token value to set (if higher than current)
     */
    public void updateToken(String lockId, long token) {
        tokenSequences.compute(lockId, (k, existing) -> {
            if (existing == null) {
                return new AtomicLong(token);
            }
            existing.updateAndGet(current -> Math.max(current, token));
            return existing;
        });
    }

    /**
     * Generates a globally unique token (not per-lock).
     * Useful for operations that need a unique identifier across all locks.
     *
     * @return A globally unique, monotonically increasing token
     */
    public long nextGlobalToken() {
        return globalSequence.incrementAndGet();
    }

    /**
     * Gets statistics about token generation.
     */
    public TokenStats getStats() {
        return new TokenStats(
                tokenSequences.size(),
                globalSequence.get(),
                tokenSequences.entrySet().stream()
                        .mapToLong(e -> e.getValue().get())
                        .sum()
        );
    }

    /**
     * Clears all token sequences (for testing purposes).
     */
    public void clear() {
        tokenSequences.clear();
        globalSequence.set(0);
        log.warn("All token sequences cleared");
    }

    /**
     * Statistics about token generation.
     */
    public record TokenStats(
            int trackedLocks,
            long globalTokenValue,
            long totalTokensIssued
    ) {}
}
