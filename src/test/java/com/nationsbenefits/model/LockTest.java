package com.nationsbenefits.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Lock model.
 */
class LockTest {

    @Test
    @DisplayName("Should create lock successfully with valid parameters")
    void shouldCreateLockSuccessfully() {
        var lockId = UUID.randomUUID().toString();
        var holderId = "client-1";
        var holderRegion = "us-east-1";
        long fencingToken = 1;
        long timeoutMs = 30000;

        var lock = Lock.create(lockId, holderId, holderRegion, fencingToken, timeoutMs);

        assertEquals(lockId, lock.lockId());
        assertEquals(holderId, lock.holderId());
        assertEquals(holderRegion, lock.holderRegion());
        assertEquals(fencingToken, lock.fencingToken());
        assertFalse(lock.isExpired());
    }

    @Test
    @DisplayName("Should throw exception for null lockId")
    void shouldThrowExceptionForNullLockId() {
        assertThrows(NullPointerException.class, () ->
                new Lock(null, "client", "region", 1, Instant.now(), Instant.now().plusSeconds(30))
        );
    }

    @Test
    @DisplayName("Should throw exception for negative fencing token")
    void shouldThrowExceptionForNegativeFencingToken() {
        assertThrows(IllegalArgumentException.class, () ->
                new Lock("lock-1", "client", "region", -1, Instant.now(), Instant.now().plusSeconds(30))
        );
    }

    @Test
    @DisplayName("Should throw exception when expiresAt is before acquiredAt")
    void shouldThrowExceptionWhenExpiresAtBeforeAcquiredAt() {
        var now = Instant.now();
        assertThrows(IllegalArgumentException.class, () ->
                new Lock("lock-1", "client", "region", 1, now, now.minusSeconds(1))
        );
    }

    @Test
    @DisplayName("Should detect expired lock")
    void shouldDetectExpiredLock() throws InterruptedException {
        var lock = Lock.create("lock-1", "client", "region", 1, 50);

        assertFalse(lock.isExpired());

        Thread.sleep(100);

        assertTrue(lock.isExpired());
    }

    @Test
    @DisplayName("Should calculate correct TTL")
    void shouldCalculateCorrectTtl() {
        var lock = Lock.create("lock-1", "client", "region", 1, 30000);

        var ttl = lock.ttlMs();
        assertTrue(ttl > 0);
        assertTrue(ttl <= 30000);
    }

    @Test
    @DisplayName("Should return zero TTL for expired lock")
    void shouldReturnZeroTtlForExpiredLock() throws InterruptedException {
        var lock = Lock.create("lock-1", "client", "region", 1, 50);

        Thread.sleep(100);

        assertEquals(0, lock.ttlMs());
    }

    @Test
    @DisplayName("Should check holder correctly")
    void shouldCheckHolderCorrectly() {
        var lock = Lock.create("lock-1", "client-1", "us-east-1", 1, 30000);

        assertTrue(lock.isHeldBy("client-1", "us-east-1"));
        assertFalse(lock.isHeldBy("client-2", "us-east-1"));
        assertFalse(lock.isHeldBy("client-1", "us-west-2"));
    }

    @Test
    @DisplayName("Should match fencing token correctly")
    void shouldMatchFencingTokenCorrectly() {
        var lock = Lock.create("lock-1", "client-1", "us-east-1", 42, 30000);

        assertTrue(lock.matchesToken(42));
        assertFalse(lock.matchesToken(41));
        assertFalse(lock.matchesToken(43));
    }

    @Test
    @DisplayName("Should extend lock correctly")
    void shouldExtendLockCorrectly() {
        var lock = Lock.create("lock-1", "client-1", "us-east-1", 1, 30000);
        var originalExpires = lock.expiresAt();

        var extended = lock.extend(10000);

        assertEquals(lock.lockId(), extended.lockId());
        assertEquals(lock.holderId(), extended.holderId());
        assertEquals(lock.fencingToken(), extended.fencingToken());
        assertEquals(lock.acquiredAt(), extended.acquiredAt());
        assertEquals(originalExpires.plusMillis(10000), extended.expiresAt());
    }

    @Test
    @DisplayName("Should validate UUID format")
    void shouldValidateUuidFormat() {
        assertTrue(Lock.isValidLockId(UUID.randomUUID().toString()));
        assertTrue(Lock.isValidLockId("550e8400-e29b-41d4-a716-446655440000"));

        assertFalse(Lock.isValidLockId(null));
        assertFalse(Lock.isValidLockId(""));
        assertFalse(Lock.isValidLockId("not-a-uuid"));
        assertFalse(Lock.isValidLockId("   "));
    }
}
