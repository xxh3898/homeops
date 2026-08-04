package dev.homeops.agent.api;

import java.time.Instant;

public record AgentStatusResponse(
        String agentId,
        String agentVersion,
        ConnectionStatus status,
        Instant lastCapturedAt,
        Instant lastSeenAt,
        boolean stale) {

    public enum ConnectionStatus {
        CONNECTED,
        STALE,
        OFFLINE
    }
}

