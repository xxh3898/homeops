package dev.homeops.system.api;

import java.time.Instant;

public record ContainerDetailResponse(
        String agentStatus,
        Instant lastUpdatedAt,
        boolean stale,
        boolean supportsContainerLogs,
        ContainerView container) {

    public ContainerDetailResponse(
            String agentStatus,
            Instant lastUpdatedAt,
            boolean stale,
            ContainerView container) {
        this(agentStatus, lastUpdatedAt, stale, false, container);
    }
}
