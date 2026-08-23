package dev.homeops.activity.api;

import java.time.Instant;

public record ActivityEventResponse(
        String id,
        Type type,
        String title,
        String status,
        Severity severity,
        Instant occurredAt,
        String context) {

    public enum Type { DEPLOYMENT, BACKUP, INCIDENT, AGENT, CONTAINER_ACTION }
    public enum Severity { INFO, WARNING, CRITICAL, RECOVERY }
}
