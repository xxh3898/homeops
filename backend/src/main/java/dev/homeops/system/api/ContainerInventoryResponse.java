package dev.homeops.system.api;

import java.time.Instant;
import java.util.List;

public record ContainerInventoryResponse(
        String agentStatus,
        Instant lastUpdatedAt,
        boolean stale,
        List<ContainerView> containers) {

    public ContainerInventoryResponse {
        containers = List.copyOf(containers);
    }
}
