package dev.homeops.agent.logs;

import java.time.Instant;
import java.util.UUID;

public record ContainerLogWork(
        UUID requestId,
        String containerId,
        int tail,
        Instant expiresAt) {
}
