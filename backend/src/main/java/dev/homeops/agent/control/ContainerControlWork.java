package dev.homeops.agent.control;

import dev.homeops.system.ContainerIdentifier;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record ContainerControlWork(
        UUID requestId,
        String containerId,
        String composeProject,
        ContainerControlOperation operation,
        Instant expiresAt) {

    private static final Pattern PROJECT_NAME =
            Pattern.compile("^[a-z0-9][a-z0-9_-]{0,62}$");

    public ContainerControlWork {
        Objects.requireNonNull(requestId, "requestId");
        containerId = ContainerIdentifier.parse(containerId).value();
        Objects.requireNonNull(composeProject, "composeProject");
        if (!PROJECT_NAME.matcher(composeProject).matches()) {
            throw new IllegalArgumentException("Compose project is invalid");
        }
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "ContainerControlWork[requestId=" + requestId
                + ", containerId=" + containerId
                + ", composeProject=redacted, operation=" + operation
                + ", expiresAt=" + expiresAt + "]";
    }
}
