package com.nationsbenefits.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FencingTokenGenerator.
 */
class FencingTokenGeneratorTest {

    private FencingTokenGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new FencingTokenGenerator();
    }

    @Test
    @DisplayName("Should generate monotonically increasing tokens for same lock")
    void shouldGenerateMonotonicallyIncreasingTokens() {
        var lockId = UUID.randomUUID().toString();

        long token1 = generator.nextToken(lockId);
        long token2 = generator.nextToken(lockId);
        long token3 = generator.nextToken(lockId);

        assertEquals(1, token1);
        assertEquals(2, token2);
        assertEquals(3, token3);
    }

    @Test
    @DisplayName("Should maintain separate sequences per lock")
    void shouldMaintainSeparateSequencesPerLock() {
        var lockId1 = UUID.randomUUID().toString();
        var lockId2 = UUID.randomUUID().toString();

        long token1a = generator.nextToken(lockId1);
        long token2a = generator.nextToken(lockId2);
        long token1b = generator.nextToken(lockId1);
        long token2b = generator.nextToken(lockId2);

        assertEquals(1, token1a);
        assertEquals(1, token2a);
        assertEquals(2, token1b);
        assertEquals(2, token2b);
    }

    @Test
    @DisplayName("Should return current token without incrementing")
    void shouldReturnCurrentTokenWithoutIncrementing() {
        var lockId = UUID.randomUUID().toString();

        // No tokens generated yet
        assertEquals(0, generator.currentToken(lockId));

        // Generate some tokens
        generator.nextToken(lockId);
        generator.nextToken(lockId);

        // Current should return last generated
        assertEquals(2, generator.currentToken(lockId));

        // Calling current again shouldn't change it
        assertEquals(2, generator.currentToken(lockId));
    }

    @Test
    @DisplayName("Should update token to higher value")
    void shouldUpdateTokenToHigherValue() {
        var lockId = UUID.randomUUID().toString();

        // Generate initial token
        generator.nextToken(lockId); // 1

        // Update to higher value
        generator.updateToken(lockId, 100);

        // Next token should be higher than updated value
        assertEquals(101, generator.nextToken(lockId));
    }

    @Test
    @DisplayName("Should not downgrade token on update")
    void shouldNotDowngradeTokenOnUpdate() {
        var lockId = UUID.randomUUID().toString();

        // Generate some tokens
        for (int i = 0; i < 50; i++) {
            generator.nextToken(lockId);
        }

        // Try to update to lower value
        generator.updateToken(lockId, 10);

        // Next token should continue from 50, not 10
        assertEquals(51, generator.nextToken(lockId));
    }

    @Test
    @DisplayName("Should handle concurrent token generation")
    void shouldHandleConcurrentTokenGeneration() throws InterruptedException {
        var lockId = UUID.randomUUID().toString();
        int numThreads = 100;

        Set<Long> generatedTokens = ConcurrentHashMap.newKeySet();
        var latch = new CountDownLatch(numThreads);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < numThreads; i++) {
                executor.submit(() -> {
                    try {
                        long token = generator.nextToken(lockId);
                        generatedTokens.add(token);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }

        // All tokens should be unique
        assertEquals(numThreads, generatedTokens.size());

        // All tokens should be in range 1 to numThreads
        for (long token : generatedTokens) {
            assertTrue(token >= 1 && token <= numThreads);
        }
    }

    @Test
    @DisplayName("Should generate unique global tokens")
    void shouldGenerateUniqueGlobalTokens() {
        Set<Long> tokens = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            tokens.add(generator.nextGlobalToken());
        }

        assertEquals(100, tokens.size());
    }

    @Test
    @DisplayName("Should return accurate stats")
    void shouldReturnAccurateStats() {
        var lockId1 = UUID.randomUUID().toString();
        var lockId2 = UUID.randomUUID().toString();

        // Generate tokens
        for (int i = 0; i < 5; i++) generator.nextToken(lockId1);
        for (int i = 0; i < 3; i++) generator.nextToken(lockId2);
        for (int i = 0; i < 2; i++) generator.nextGlobalToken();

        var stats = generator.getStats();

        assertEquals(2, stats.trackedLocks());
        assertEquals(2, stats.globalTokenValue());
        assertEquals(8, stats.totalTokensIssued()); // 5 + 3
    }

    @Test
    @DisplayName("Should clear all state")
    void shouldClearAllState() {
        var lockId = UUID.randomUUID().toString();

        generator.nextToken(lockId);
        generator.nextGlobalToken();

        generator.clear();

        assertEquals(0, generator.currentToken(lockId));
        var stats = generator.getStats();
        assertEquals(0, stats.trackedLocks());
        assertEquals(0, stats.globalTokenValue());
    }
}
