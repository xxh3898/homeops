package dev.homeops.agent.control.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import dev.homeops.agent.control.ContainerControlReasonCode;
import dev.homeops.agent.control.ContainerControlResultStatus;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record AgentControlResultRequest(
        @NotNull UUID requestId,
        @NotNull ContainerControlResultStatus status,
        @NotNull ContainerControlReasonCode reasonCode,
        @NotNull Instant finishedAt) {

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown container control result field");
    }
}
