package dev.homeops.notification;

import java.util.UUID;

record NotificationClaim(
        UUID id,
        UUID leaseToken,
        int attemptCount,
        NotificationSeverity severity,
        String payloadJson) {

    @Override
    public String toString() {
        return "NotificationClaim[id=" + id + ", attemptCount=" + attemptCount
                + ", severity=" + severity + ", payload=redacted]";
    }
}
