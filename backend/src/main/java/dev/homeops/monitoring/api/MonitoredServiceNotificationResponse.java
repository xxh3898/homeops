package dev.homeops.monitoring.api;

import java.util.UUID;

public record MonitoredServiceNotificationResponse(UUID id, boolean notificationEnabled) { }
