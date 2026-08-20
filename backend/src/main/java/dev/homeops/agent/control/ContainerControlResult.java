package dev.homeops.agent.control;

import java.time.Instant;
import java.util.Objects;

public record ContainerControlResult(
        ContainerControlResultStatus status,
        ContainerControlReasonCode reasonCode,
        Instant finishedAt) {

    public ContainerControlResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(finishedAt, "finishedAt");
        if (!reasonCode.isValidFor(status)) {
            throw new IllegalArgumentException("Control result status and reason are inconsistent");
        }
    }
}
