package dev.homeops.agent.control.api;

import dev.homeops.agent.control.ContainerActionAuditRecord;
import dev.homeops.agent.control.ContainerActionStatus;
import dev.homeops.agent.control.ContainerControlOperation;
import java.time.Instant;
import java.util.UUID;

public record ContainerActionResponse(
        UUID operationId,
        String containerId,
        ContainerControlOperation operation,
        ContainerActionStatus status,
        String reasonCode,
        Instant requestedAt,
        Instant completedAt) {

    static ContainerActionResponse from(ContainerActionAuditRecord record) {
        return new ContainerActionResponse(
                record.operationId(),
                record.containerId(),
                record.operation(),
                record.status(),
                record.reasonCode(),
                record.requestedAt(),
                record.completedAt());
    }
}
