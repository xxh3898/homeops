package dev.homeops.notification;

import java.util.Objects;
import java.util.UUID;

record NotificationEventReference(UUID id, NotificationStatus status) {
    NotificationEventReference {
        Objects.requireNonNull(id, "Notification event id must be configured");
        Objects.requireNonNull(status, "Notification event status must be configured");
    }
}
