package dev.homeops.agent.domain;

import dev.homeops.agent.api.AgentSnapshotRequest;
import java.time.Instant;

public record ReceivedAgentSnapshot(
        AgentSnapshotRequest snapshot,
        Instant receivedAt) {
}

