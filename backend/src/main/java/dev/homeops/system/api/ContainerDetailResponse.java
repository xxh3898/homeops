package dev.homeops.system.api;

import java.time.Instant;

public record ContainerDetailResponse(
        String agentStatus,
        Instant lastUpdatedAt,
        boolean stale,
        ContainerView container) {
}
