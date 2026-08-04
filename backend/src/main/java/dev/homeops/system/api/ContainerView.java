package dev.homeops.system.api;

import dev.homeops.agent.api.AgentSnapshotRequest;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerSnapshot;
import java.time.Instant;
import java.util.List;

public record ContainerView(
        String id,
        String name,
        String composeProject,
        String image,
        String state,
        String health,
        String status,
        Instant startedAt,
        long restartCount,
        Double cpuUsagePercent,
        Long memoryUsageBytes,
        Long memoryLimitBytes,
        List<PortView> ports,
        boolean managed) {

    public static ContainerView from(ContainerSnapshot snapshot) {
        return new ContainerView(
                snapshot.id().substring(0, Math.min(12, snapshot.id().length())),
                snapshot.name(),
                snapshot.composeProject(),
                snapshot.image(),
                snapshot.state().name(),
                snapshot.health().name(),
                snapshot.status(),
                snapshot.startedAt(),
                snapshot.restartCount(),
                snapshot.cpuUsagePercent(),
                snapshot.memoryUsageBytes(),
                snapshot.memoryLimitBytes(),
                snapshot.ports().stream().map(PortView::from).toList(),
                snapshot.managed());
    }

    public record PortView(
            int privatePort,
            Integer publicPort,
            String type) {

        static PortView from(AgentSnapshotRequest.ContainerPort port) {
            return new PortView(
                    port.privatePort(),
                    port.publicPort(),
                    port.type().name());
        }
    }
}
