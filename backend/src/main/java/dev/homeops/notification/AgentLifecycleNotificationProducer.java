package dev.homeops.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AgentLifecycleNotificationProducer {
    static final String STALE = "AGENT_STALE";
    static final String RECOVERED = "AGENT_RECOVERED";
    static final String VERSION_CHANGED = "AGENT_VERSION_CHANGED";
    private static final Duration MAXIMUM_REPORTED_DURATION = Duration.ofDays(30);

    private final NotificationOutbox outbox;

    public AgentLifecycleNotificationProducer(NotificationOutbox outbox) {
        this.outbox = outbox;
    }

    public void recordStale(
            UUID staleSnapshotId,
            String agentId,
            String agentVersion,
            Instant staleSince,
            Instant observedAt) {
        outbox.enqueue(intent(
                staleSnapshotId,
                STALE,
                NotificationSeverity.CRITICAL,
                null,
                observedAt,
                "Agent snapshot became stale",
                "The expected Agent stopped reporting fresh snapshots.",
                List.of(
                        new NotificationField("Agent", agentId, true),
                        new NotificationField("Version", agentVersion, true),
                        new NotificationField("Status", "STALE", true),
                        new NotificationField(
                                "Duration", durationSummary(staleSince, observedAt), true))));
    }

    public void recordRecovered(
            UUID staleSnapshotId,
            String agentId,
            String currentVersion,
            Instant staleSince,
            Instant recoveredAt) {
        sentStaleRoot(staleSnapshotId).ifPresent(root -> outbox.enqueue(intent(
                staleSnapshotId,
                RECOVERED,
                NotificationSeverity.RECOVERY,
                root.id(),
                recoveredAt,
                "Agent snapshot recovered",
                "The expected Agent resumed fresh snapshot delivery.",
                List.of(
                        new NotificationField("Agent", agentId, true),
                        new NotificationField("Version", currentVersion, true),
                        new NotificationField("Status", "RECOVERED", true),
                        new NotificationField(
                                "Duration", durationSummary(staleSince, recoveredAt), true)))));
    }

    public void recordVersionChanged(
            UUID acceptedSnapshotId,
            String agentId,
            String currentVersion,
            Instant occurredAt) {
        outbox.enqueue(intent(
                acceptedSnapshotId,
                VERSION_CHANGED,
                NotificationSeverity.INFO,
                null,
                occurredAt,
                "Agent version changed",
                "The expected Agent reported a different version.",
                List.of(
                        new NotificationField("Agent", agentId, true),
                        new NotificationField("Version", currentVersion, true),
                        new NotificationField("Status", "VERSION_CHANGED", true))));
    }

    private Optional<NotificationEventReference> sentStaleRoot(UUID staleSnapshotId) {
        return outbox.findEvent(NotificationSourceType.AGENT, staleSnapshotId, STALE)
                .filter(root -> root.status() == NotificationStatus.SENT);
    }

    private static NotificationIntent intent(
            UUID sourceId,
            String eventCode,
            NotificationSeverity severity,
            UUID parentNotificationId,
            Instant occurredAt,
            String title,
            String summary,
            List<NotificationField> fields) {
        return new NotificationIntent(
                NotificationSourceType.AGENT,
                sourceId,
                severity,
                eventCode,
                "agent:" + sourceId + ":" + eventCode,
                parentNotificationId,
                occurredAt,
                new NotificationPayload(
                        eventCode, title, summary, fields, occurredAt));
    }

    private static String durationSummary(Instant startedAt, Instant eventAt) {
        Duration elapsed = Duration.between(startedAt, eventAt);
        if (elapsed.isNegative()) {
            elapsed = Duration.ZERO;
        }
        if (elapsed.compareTo(MAXIMUM_REPORTED_DURATION) > 0) {
            return "30d+";
        }
        long totalSeconds = elapsed.toSeconds();
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        long totalMinutes = totalSeconds / 60;
        if (totalMinutes < 60) {
            return totalMinutes + "m";
        }
        long totalHours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (totalHours < 24) {
            return totalHours + "h " + minutes + "m";
        }
        long days = totalHours / 24;
        long hours = totalHours % 24;
        return days + "d " + hours + "h";
    }
}
