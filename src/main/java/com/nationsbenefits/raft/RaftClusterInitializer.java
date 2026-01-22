package com.nationsbenefits.raft;

import com.nationsbenefits.config.RaftConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Initializes Raft cluster connections on startup.
 * Creates RaftPeerClient instances for each peer node in the cluster.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RaftClusterInitializer {

    private final RaftConfig raftConfig;
    private final RaftNode raftNode;

    private final List<RaftPeerClient> peerClients = new ArrayList<>();

    @PostConstruct
    public void initializeCluster() {
        var peers = raftConfig.getPeerNodes();

        if (peers.isEmpty()) {
            log.info("No Raft peers configured - running as single-node cluster");
            raftNode.startElectionProcess();
            return;
        }

        log.info("Initializing Raft cluster with {} peer(s)", peers.size());

        for (var peer : peers) {
            try {
                var client = new RaftPeerClient(
                        peer.getNodeId(),
                        peer.getHost(),
                        peer.getPort()
                );
                peerClients.add(client);
                raftNode.addPeer(client);
                log.info("Added Raft peer: {} at {}:{}", peer.getNodeId(), peer.getHost(), peer.getPort());
            } catch (Exception e) {
                log.error("Failed to create peer client for {}: {}", peer.getNodeId(), e.getMessage());
            }
        }

        log.info("Raft cluster initialization complete - {} peer(s) connected", peerClients.size());

        // Start election process after all peers are connected
        raftNode.startElectionProcess();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Raft peer connections");
        for (var client : peerClients) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing peer client {}: {}", client.nodeId(), e.getMessage());
            }
        }
        peerClients.clear();
    }
}
