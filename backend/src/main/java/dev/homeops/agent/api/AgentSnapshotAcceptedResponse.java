package dev.homeops.agent.api;

import java.time.Instant;
import java.util.UUID;

public record AgentSnapshotAcceptedResponse(
        UUID snapshotId,
        Instant receivedAt,
        boolean duplicate) {
}

