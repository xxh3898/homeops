package dev.homeops.notification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class NotificationIntent {
    private final NotificationSourceType sourceType;
    private final UUID sourceId;
    private final NotificationSeverity severity;
    private final String eventType;
    private final String deduplicationMaterial;
    private final UUID parentNotificationId;
    private final Instant occurredAt;
    private final NotificationPayload payload;

    public NotificationIntent(
            NotificationSourceType sourceType,
            UUID sourceId,
            NotificationSeverity severity,
            String eventType,
            String deduplicationMaterial,
            UUID parentNotificationId,
            Instant occurredAt,
            NotificationPayload payload) {
        this.sourceType = Objects.requireNonNull(sourceType, "Notification source type must be configured");
        this.sourceId = Objects.requireNonNull(sourceId, "Notification source id must be configured");
        this.severity = Objects.requireNonNull(severity, "Notification severity must be configured");
        if (eventType == null || !eventType.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Notification event type must use the bounded allowlist");
        }
        if (deduplicationMaterial == null || deduplicationMaterial.isBlank()
                || deduplicationMaterial.length() > 256) {
            throw new IllegalArgumentException("Notification deduplication material must be bounded");
        }
        this.eventType = eventType;
        this.deduplicationMaterial = deduplicationMaterial;
        this.parentNotificationId = parentNotificationId;
        this.occurredAt = Objects.requireNonNull(occurredAt, "Notification occurrence time must be configured");
        this.payload = Objects.requireNonNull(payload, "Notification payload must be configured");
    }

    public NotificationSourceType sourceType() {
        return sourceType;
    }

    public UUID sourceId() {
        return sourceId;
    }

    public NotificationSeverity severity() {
        return severity;
    }

    public String eventType() {
        return eventType;
    }

    String deduplicationMaterial() {
        return deduplicationMaterial;
    }

    public UUID parentNotificationId() {
        return parentNotificationId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public NotificationPayload payload() {
        return payload;
    }

    @Override
    public String toString() {
        return "NotificationIntent[sourceType=" + sourceType + ", sourceId=" + sourceId
                + ", eventType=" + eventType + ", content=redacted]";
    }
}
