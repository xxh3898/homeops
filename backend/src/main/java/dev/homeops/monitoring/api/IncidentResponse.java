package dev.homeops.monitoring.api;

import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(UUID id, UUID serviceId, String severity, String status,
        String title, Instant openedAt, Instant resolvedAt, Instant lastObservedAt) { }
