package com.nationsbenefits.service;

import com.nationsbenefits.model.LockStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LockStore.
 */
class LockStoreTest {

    private LockStore lockStore;
    private FencingTokenGenerator tokenGenerator;

    @BeforeEach
    void setUp() {
        tokenGenerator = new FencingTokenGenerator();
        lockStore = new LockStore(tokenGenerator);
    }

    @Test
    @DisplayName("Should acquire lock successfully when not held")
    void shouldAcquireLockWhenNotHeld() {
        var lockId = UUID.randomUUID().toString();
        var clientId = "client-1";
        var regionId = "us-east-1";

        var result = lockStore.tryAcquire(lockId, clientId, regionId, 30000);

        assertTrue(result.isSuccess());
        var lock = result.getValue();
        assertEquals(lockId, lock.lockId());
        assertEquals(clientId, lock.holderId());
        assertEquals(regionId, lock.holderRegion());
        assertTrue(lock.fencingToken() > 0);
        assertFalse(lock.isExpired());
    }

    @Test
    @DisplayName("Should fail to acquire lock when already held")
    void shouldFailToAcquireWhenAlreadyHeld() {
        var lockId = UUID.randomUUID().toString();
        var clientId1 = "client-1";
        var clientId2 = "client-2";
        var regionId = "us-east-1";

        // First acquisition should succeed
        var result1 = lockStore.tryAcquire(lockId, clientId1, regionId, 30000);
        assertTrue(result1.isSuccess());

        // Second acquisition should fail
        var result2 = lockStore.tryAcquire(lockId, clientId2, regionId, 30000);
        assertFalse(result2.isSuccess());
        assertEquals(LockStatus.ALREADY_LOCKED, result2.getError().status());
    }

    @Test
    @DisplayName("Should release lock successfully with valid token")
    void shouldReleaseLockWithValidToken() {
        var lockId = UUID.randomUUID().toString();
        var clientId = "client-1";
        var regionId = "us-east-1";

        // Acquire
        var acquireResult = lockStore.tryAcquire(lockId, clientId, regionId, 30000);
        assertTrue(acquireResult.isSuccess());
        var lock = acquireResult.getValue();

        // Release
        var releaseResult = lockStore.release(lockId, clientId, lock.fencingToken());
        assertTrue(releaseResult.isSuccess());

        // Verify lock is released
        assertFalse(lockStore.isLocked(lockId));
    }

    @Test
    @DisplayName("Should fail to release lock with invalid token")
    void shouldFailToReleaseWithInvalidToken() {
        var lockId = UUID.randomUUID().toString();
        var clientId = "client-1";
        var regionId = "us-east-1";

        // Acquire
        var acquireResult = lockStore.tryAcquire(lockId, clientId, regionId, 30000);
        assertTrue(acquireResult.isSuccess());

        // Try to release with wrong token
        var releaseResult = lockStore.release(lockId, clientId, 999999L);
        assertFalse(releaseResult.isSuccess());
        assertEquals(LockStatus.INVALID_TOKEN, releaseResult.getError().status());
    }

    @Test
    @DisplayName("Should allow re-acquiring expired lock")
    void shouldAllowReacquiringExpiredLock() throws InterruptedException {
        var lockId = UUID.randomUUID().toString();
        var clientId1 = "client-1";
        var clientId2 = "client-2";
        var regionId = "us-east-1";

        // Acquire with short timeout
        var result1 = lockStore.tryAcquire(lockId, clientId1, regionId, 50);
        assertTrue(result1.isSuccess());

        // Wait for expiration
        Thread.sleep(100);

        // Second acquisition should succeed after expiration
        var result2 = lockStore.tryAcquire(lockId, clientId2, regionId, 30000);
        assertTrue(result2.isSuccess());
        assertEquals(clientId2, result2.getValue().holderId());
    }

    @Test
    @DisplayName("Should handle concurrent lock acquisition attempts")
    void shouldHandleConcurrentAcquisition() throws InterruptedException {
        var lockId = UUID.randomUUID().toString();
        var regionId = "us-east-1";
        int numThreads = 10;

        var successCount = new AtomicInteger(0);
        var latch = new CountDownLatch(numThreads);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < numThreads; i++) {
                final int clientNum = i;
                executor.submit(() -> {
                    try {
                        var result = lockStore.tryAcquire(lockId, "client-" + clientNum, regionId, 30000);
                        if (result.isSuccess()) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }

        // Only one thread should have acquired the lock
        assertEquals(1, successCount.get());
    }

    @Test
    @DisplayName("Should return correct TTL for active lock")
    void shouldReturnCorrectTtl() {
        var lockId = UUID.randomUUID().toString();
        var clientId = "client-1";
        var regionId = "us-east-1";
        long timeoutMs = 30000;

        var result = lockStore.tryAcquire(lockId, clientId, regionId, timeoutMs);
        assertTrue(result.isSuccess());

        var lock = lockStore.get(lockId);
        assertTrue(lock.isPresent());
        assertTrue(lock.get().ttlMs() > 0);
        assertTrue(lock.get().ttlMs() <= timeoutMs);
    }

    @Test
    @DisplayName("Should increment fencing tokens monotonically")
    void shouldIncrementFencingTokensMonotonically() {
        var lockId = UUID.randomUUID().toString();
        var clientId = "client-1";
        var regionId = "us-east-1";

        // Acquire and release multiple times
        long previousToken = 0;
        for (int i = 0; i < 5; i++) {
            var acquireResult = lockStore.tryAcquire(lockId, clientId, regionId, 30000);
            assertTrue(acquireResult.isSuccess());

            var lock = acquireResult.getValue();
            assertTrue(lock.fencingToken() > previousToken);
            previousToken = lock.fencingToken();

            lockStore.release(lockId, clientId, lock.fencingToken());
        }
    }

    @Test
    @DisplayName("Should return all active locks")
    void shouldReturnAllActiveLocks() {
        var regionId = "us-east-1";

        // Acquire multiple locks
        for (int i = 0; i < 5; i++) {
            var lockId = UUID.randomUUID().toString();
            lockStore.tryAcquire(lockId, "client-" + i, regionId, 30000);
        }

        var activeLocks = lockStore.getAllActiveLocks();
        assertEquals(5, activeLocks.size());
    }
}
