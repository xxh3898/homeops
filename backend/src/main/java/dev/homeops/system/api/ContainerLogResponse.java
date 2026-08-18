package dev.homeops.system.api;

import dev.homeops.agent.logs.ContainerLogLine;
import dev.homeops.agent.logs.ContainerLogResult;
import java.time.Instant;
import java.util.List;

public record ContainerLogResponse(
        String containerId,
        int requestedTail,
        Instant collectedAt,
        boolean truncated,
        boolean redactionApplied,
        List<Line> lines) {

    public ContainerLogResponse {
        lines = List.copyOf(lines);
    }

    static ContainerLogResponse from(
            String containerId,
            int requestedTail,
            ContainerLogResult result) {
        return new ContainerLogResponse(
                containerId,
                requestedTail,
                result.collectedAt(),
                result.truncated(),
                result.redactionApplied(),
                result.lines().stream().map(Line::from).toList());
    }

    public record Line(
            Instant timestamp,
            String stream,
            String message) {

        static Line from(ContainerLogLine line) {
            return new Line(
                    line.timestamp(),
                    line.stream().name(),
                    line.message());
        }
    }
}
