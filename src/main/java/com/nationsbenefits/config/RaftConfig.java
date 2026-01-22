package com.nationsbenefits.config;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for Raft consensus within a regional cluster.
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "lockmgr.raft")
@Getter
@Setter
public class RaftConfig {

    private String nodeId;
    private long electionTimeoutMs = 150;
    private long heartbeatIntervalMs = 50;
    private List<ClusterNode> clusterNodes = new ArrayList<>();

    /**
     * Comma-separated list of peers in format: nodeId:host:port,nodeId:host:port
     * Example: node-2:localhost:9091,node-3:localhost:9092
     */
    private String peers;

    /**
     * Returns other nodes in the cluster (excluding self).
     * Parses from either clusterNodes list or peers string.
     */
    public List<ClusterNode> getPeerNodes() {
        List<ClusterNode> allNodes = new ArrayList<>(clusterNodes);

        // Parse peers string if provided
        if (peers != null && !peers.isBlank()) {
            for (String peerSpec : peers.split(",")) {
                String trimmed = peerSpec.trim();
                if (trimmed.isEmpty()) continue;

                String[] parts = trimmed.split(":");
                if (parts.length == 3) {
                    ClusterNode node = new ClusterNode();
                    node.setNodeId(parts[0].trim());
                    node.setHost(parts[1].trim());
                    try {
                        node.setPort(Integer.parseInt(parts[2].trim()));
                        allNodes.add(node);
                    } catch (NumberFormatException e) {
                        log.warn("Invalid port in peer spec '{}': {}", trimmed, e.getMessage());
                    }
                } else {
                    log.warn("Invalid peer spec '{}' - expected format: nodeId:host:port", trimmed);
                }
            }
        }

        // Filter out self
        return allNodes.stream()
                .filter(node -> !node.getNodeId().equals(nodeId))
                .toList();
    }

    @Data
    public static class ClusterNode {
        private String nodeId;
        private String host;
        private int port;

        public String getAddress() {
            return host + ":" + port;
        }
    }
}
