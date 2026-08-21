package dev.homeops.agent.control.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import dev.homeops.agent.control.ContainerControlOperation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ContainerActionRequest(
        @NotNull ContainerControlOperation operation,
        @NotNull @Size(min = 1, max = 64) String confirmation) {

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown container action field");
    }
}
