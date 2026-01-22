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
 * Configuration for cross-region communication.
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "lockmgr.region")
@Getter
@Setter
public class RegionConfig {

    private String regionId;
    private int quorumTimeoutMs = 5000;
    private int regionPort = 9091;

    /**
     * Comma-separated list of region peers in format: regionId:host:port,regionId:host:port
     * Example: us-west:us-west-node-1:9090,eu-west:eu-west-node-1:9090
     */
    private String peersString;

    /**
     * Returns peer regions parsed from peersString.
     */
    public List<PeerRegion> getPeers() {
        List<PeerRegion> peerList = new ArrayList<>();

        if (peersString != null && !peersString.isBlank()) {
            for (String peerSpec : peersString.split(",")) {
                String trimmed = peerSpec.trim();
                if (trimmed.isEmpty()) continue;

                String[] parts = trimmed.split(":");
                if (parts.length == 3) {
                    PeerRegion peer = new PeerRegion();
                    peer.setRegionId(parts[0].trim());
                    peer.setHost(parts[1].trim());
                    try {
                        peer.setPort(Integer.parseInt(parts[2].trim()));
                        peerList.add(peer);
                        log.debug("Parsed region peer: {} at {}:{}", peer.getRegionId(), peer.getHost(), peer.getPort());
                    } catch (NumberFormatException e) {
                        log.warn("Invalid port in region peer spec '{}': {}", trimmed, e.getMessage());
                    }
                } else {
                    log.warn("Invalid region peer spec '{}' - expected format: regionId:host:port", trimmed);
                }
            }
        }

        return peerList;
    }

    /**
     * Returns the total number of regions (including self).
     */
    public int getTotalRegions() {
        return getPeers().size() + 1;
    }

    /**
     * Returns the quorum size needed for consensus.
     */
    public int getQuorumSize() {
        return getTotalRegions() / 2 + 1;
    }

    @Data
    public static class PeerRegion {
        private String regionId;
        private String host;
        private int port;

        public String getAddress() {
            return host + ":" + port;
        }
    }
}
