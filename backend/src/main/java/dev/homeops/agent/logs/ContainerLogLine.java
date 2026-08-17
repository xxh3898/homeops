package dev.homeops.agent.logs;

import java.time.Instant;

public record ContainerLogLine(
        Instant timestamp,
        ContainerLogStream stream,
        String message) {
}
