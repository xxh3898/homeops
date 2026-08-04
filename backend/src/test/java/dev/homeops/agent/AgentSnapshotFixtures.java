package dev.homeops.agent;

import dev.homeops.agent.api.AgentSnapshotRequest;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerHealth;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerSnapshot;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerState;
import dev.homeops.agent.api.AgentSnapshotRequest.HostSnapshot;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerPort;
import dev.homeops.agent.api.AgentSnapshotRequest.PortType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class AgentSnapshotFixtures {

    private AgentSnapshotFixtures() {
    }

    static AgentSnapshotRequest snapshot(UUID snapshotId, Instant capturedAt) {
        return new AgentSnapshotRequest(
                snapshotId,
                "local-mac",
                "0.1.0",
                capturedAt,
                new HostSnapshot(
                        12.5,
                        16_000,
                        8_000,
                        100_000,
                        40_000,
                        7_200),
                List.of(new ContainerSnapshot(
                        "0123456789abcdef",
                        "example-api",
                        "example",
                        "example/api:sha-test",
                        ContainerState.RUNNING,
                        ContainerHealth.HEALTHY,
                        "Up 2 hours (healthy)",
                        capturedAt.minusSeconds(7_200),
                        0,
                        2.5,
                        256L,
                        512L,
                        List.of(new ContainerPort(8080, 13080, PortType.TCP)),
                        false)));
    }
}
