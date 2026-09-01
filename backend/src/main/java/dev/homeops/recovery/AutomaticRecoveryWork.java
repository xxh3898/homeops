package dev.homeops.recovery;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AutomaticRecoveryWork(
        UUID requestId,
        AutomaticRecoveryProject project,
        AutomaticRecoveryTarget target,
        AutomaticRecoveryAction action,
        Instant expiresAt) {

    public AutomaticRecoveryWork {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "AutomaticRecoveryWork[requestId=" + requestId
                + ", project=" + project.wireValue()
                + ", target=" + target.wireValue()
                + ", action=" + action
                + ", expiresAt=" + expiresAt + "]";
    }
}
