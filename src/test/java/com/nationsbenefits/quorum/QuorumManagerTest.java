package com.nationsbenefits.quorum;

import com.nationsbenefits.config.RaftConfig;
import com.nationsbenefits.config.RegionConfig;
import com.nationsbenefits.model.LockStatus;
import com.nationsbenefits.raft.RaftNode;
import com.nationsbenefits.raft.RaftStateMachine;
import com.nationsbenefits.service.FencingTokenGenerator;
import com.nationsbenefits.service.LockStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QuorumManager.
 */
class QuorumManagerTest {

    private QuorumManager quorumManager;
    private LockStore lockStore;
    private RegionConfig regionConfig;
    private RaftNode raftNode;

    @BeforeEach
    void setUp() {
        var tokenGenerator = new FencingTokenGenerator();
        lockStore = new LockStore(tokenGenerator);
        regionConfig = new RegionConfig();
        regionConfig.setRegionId("us-east-1");
        regionConfig.setQuorumTimeoutMs(1000);
        regionConfig.setPeersString(""); // No peers for single-region test

        // Create a test RaftNode that simulates being the leader
        var raftConfig = new RaftConfig();
        raftConfig.setNodeId("test-node-1");
        var stateMachine = new RaftStateMachine(lockStore);
        raftNode = new TestRaftNode(raftConfig, stateMachine, tokenGenerator);

        quorumManager = new QuorumManager(regionConfig, lockStore, raftNode);
        quorumManager.init();
    }

    /**
     * Test RaftNode that simulates being a leader for testing purposes.
     */
    private static class TestRaftNode extends RaftNode {
        TestRaftNode(RaftConfig config, RaftStateMachine stateMachine,
                     FencingTokenGenerator tokenGenerator) {
            super(config, null, stateMachine, tokenGenerator);
        }

        @Override
        public boolean isLeader() {
            return false; // For quorum tests, we don't need to be leader
        }

        @Override
        public void init() {
            // No-op for testing
        }

        @Override
        public void startElectionProcess() {
            // No-op for testing
        }

        @Override
        public void stop() {
            // No-op for testing
        }
    }

    @Test
    @DisplayName("Should achieve quorum in single region setup")
    void shouldAchieveQuorumInSingleRegion() throws Exception {
        var lockId = UUID.randomUUID().toString();
        var clientId = "client-1";
        long fencingToken = 1;
        long timeoutMs = 30000;

        var result = quorumManager.requestLockQuorum(lockId, clientId, fencingToken, timeoutMs)
                .get(5, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        var quorumResult = result.getValue();
        assertEquals(1, quorumResult.votesReceived());
        assertEquals(1, quorumResult.totalRegions());
        assertTrue(quorumResult.grantingRegions().contains("us-east-1"));
    }

    @Test
    @DisplayName("Should deny quorum when lock already exists locally")
    void shouldDenyQuorumWhenLockExistsLocally() throws Exception {
        var lockId = UUID.randomUUID().toString();
        var clientId1 = "client-1";
        var clientId2 = "client-2";

        // Pre-acquire the lock
        lockStore.tryAcquire(lockId, clientId1, "us-east-1", 30000);

        // Try to get quorum for same lock
        var result = quorumManager.requestLockQuorum(lockId, clientId2, 1, 30000)
                .get(5, TimeUnit.SECONDS);

        assertFalse(result.isSuccess());
        assertEquals(LockStatus.ALREADY_LOCKED, result.getError().status());
    }

    @Test
    @DisplayName("Should handle vote request when lock not held")
    void shouldHandleVoteRequestWhenNotHeld() {
        var request = new QuorumManager.LockVoteRequest(
                UUID.randomUUID().toString(),
                "us-west-2",
                "client-1",
                30000,
                1,
                System.currentTimeMillis()
        );

        var response = quorumManager.handleLockVoteRequest(request);

        assertTrue(response.granted());
        assertEquals("us-east-1", response.respondingRegion());
        assertNull(response.denialReason());
    }

    @Test
    @DisplayName("Should deny vote request when lock already held")
    void shouldDenyVoteRequestWhenLockHeld() {
        var lockId = UUID.randomUUID().toString();

        // Pre-acquire the lock
        var acquireResult = lockStore.tryAcquire(lockId, "client-1", "us-east-1", 30000);
        var lock = acquireResult.getValue();

        var request = new QuorumManager.LockVoteRequest(
                lockId,
                "us-west-2",
                "client-2",
                30000,
                2,
                System.currentTimeMillis()
        );

        var response = quorumManager.handleLockVoteRequest(request);

        assertFalse(response.granted());
        assertEquals("us-east-1", response.currentHolderRegion());
        assertEquals("client-1", response.currentHolderClient());
        assertEquals(lock.fencingToken(), response.currentFencingToken());
    }

    @Test
    @DisplayName("Should handle lock acquired notification")
    void shouldHandleLockAcquiredNotification() {
        var lockId = UUID.randomUUID().toString();
        var notification = new QuorumManager.LockAcquiredNotification(
                lockId,
                "us-west-2",
                "client-1",
                1,
                System.currentTimeMillis() + 30000,
                "us-west-2"
        );

        quorumManager.handleLockAcquiredNotification(notification);

        // Lock should now be present in local store
        var lock = lockStore.get(lockId);
        assertTrue(lock.isPresent());
        assertEquals("us-west-2", lock.get().holderRegion());
        assertEquals("client-1", lock.get().holderId());
        assertEquals(1, lock.get().fencingToken());
    }

    @Test
    @DisplayName("Should handle lock released notification")
    void shouldHandleLockReleasedNotification() {
        var lockId = UUID.randomUUID().toString();

        // First acquire the lock
        var acquireResult = lockStore.tryAcquire(lockId, "client-1", "us-west-2", 30000);
        var lock = acquireResult.getValue();

        var notification = new QuorumManager.LockReleasedNotification(
                lockId,
                lock.fencingToken(),
                "us-west-2"
        );

        quorumManager.handleLockReleasedNotification(notification);

        // Lock should no longer be present
        assertFalse(lockStore.isLocked(lockId));
    }
}
