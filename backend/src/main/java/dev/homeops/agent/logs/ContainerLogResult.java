package dev.homeops.agent.logs;

import java.util.List;

public record ContainerLogResult(
        ContainerLogResultStatus status,
        List<ContainerLogLine> lines,
        boolean truncated) {

    public ContainerLogResult {
        lines = List.copyOf(lines);
    }
}
