package dev.homeops.recovery;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AutomaticRecoveryAttempt(
        UUID id,
        UUID incidentId,
        UUID serviceId,
        AutomaticRecoveryProject project,
        AutomaticRecoveryTarget target,
        AutomaticRecoveryAction action,
        AutomaticRecoveryStatus status,
        AutomaticRecoveryReasonCode reasonCode,
        Instant requestedAt,
        Instant dispatchedAt,
        Instant startedAt,
        Instant completedAt,
        AutomaticRecoveryHealth preHealth,
        AutomaticRecoveryHealth postHealth,
        Integer restartCount) {

    public AutomaticRecoveryAttempt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if ((project == null) != (target == null)) {
            throw new IllegalArgumentException("Automatic recovery target identity is incomplete");
        }
        if (status.terminal() && (reasonCode == null || completedAt == null)) {
            throw new IllegalArgumentException("Terminal automatic recovery evidence is incomplete");
        }
    }

    public String activityContext() {
        return project == null ? "unmapped" : project.wireValue() + "/" + target.wireValue();
    }
}
