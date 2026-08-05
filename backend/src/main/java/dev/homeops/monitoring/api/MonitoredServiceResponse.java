package dev.homeops.monitoring.api;

import java.util.UUID;

public record MonitoredServiceResponse(UUID id, String name, String url, String method,
        int expectedStatus, int timeoutMs, int intervalSeconds, int failureThreshold,
        int recoveryThreshold, String severity, boolean enabled, boolean notificationEnabled) { }
