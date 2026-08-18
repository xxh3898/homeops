package dev.homeops.agent.logs.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import dev.homeops.agent.logs.ContainerLogLine;
import dev.homeops.agent.logs.ContainerLogResultStatus;
import dev.homeops.agent.logs.ContainerLogStream;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentLogResultRequest(
        @NotNull UUID requestId,
        @NotNull ContainerLogResultStatus status,
        @NotNull Instant collectedAt,
        @NotNull @Size(max = 200) List<@NotNull @Valid Line> lines,
        boolean truncated,
        @NotNull Boolean redactionApplied) {

    public AgentLogResultRequest {
        lines = lines == null ? null : List.copyOf(lines);
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown container log result field");
    }

    public record Line(
            Instant timestamp,
            @NotNull ContainerLogStream stream,
            @NotNull @Size(max = 8192) String message) {

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown container log line field");
        }

        public ContainerLogLine toDomain() {
            return new ContainerLogLine(timestamp, stream, message);
        }
    }
}
