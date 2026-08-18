package dev.homeops.agent.logs;

import java.time.Instant;
import java.util.List;

public record ContainerLogResult(
        ContainerLogResultStatus status,
        List<ContainerLogLine> lines,
        boolean truncated,
        Instant collectedAt,
        boolean redactionApplied) {

    public ContainerLogResult {
        lines = List.copyOf(lines);
    }
}
