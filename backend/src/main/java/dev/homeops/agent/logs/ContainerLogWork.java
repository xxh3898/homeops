package dev.homeops.agent.logs;

import java.util.UUID;

public record ContainerLogWork(
        UUID requestId,
        String containerId,
        int tail) {
}
