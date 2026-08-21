package dev.homeops.agent.control;

import dev.homeops.system.ContainerIdentifier;
import dev.homeops.system.InvalidContainerIdentifierException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ContainerActionAuditRecord(
        UUID operationId,
        String idempotencyKey,
        String principal,
        String containerId,
        ContainerControlOperation operation,
        ContainerActionStatus status,
        String reasonCode,
        Instant requestedAt,
        Instant completedAt) {

    public ContainerActionAuditRecord {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(containerId, "containerId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }

    public boolean matches(
            String expectedPrincipal,
            String expectedContainerId,
            ContainerControlOperation expectedOperation) {
        return principal.equals(expectedPrincipal)
                && containerId.equals(expectedContainerId)
                && operation == expectedOperation;
    }

    public boolean hasPublicIdentifier() {
        try {
            ContainerIdentifier.parse(containerId);
            return true;
        } catch (InvalidContainerIdentifierException exception) {
            return false;
        }
    }
}
