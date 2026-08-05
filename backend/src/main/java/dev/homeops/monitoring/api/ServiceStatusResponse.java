package dev.homeops.monitoring.api;

import java.time.Instant;
import java.util.UUID;

public record ServiceStatusResponse(
        UUID serviceId,
        String name,
        boolean enabled,
        String status,
        Instant checkedAt,
        Integer httpStatus,
        Integer responseTimeMs,
        boolean incidentOpen) { }
