package dev.homeops.notification;

import dev.homeops.monitoring.MonitoredServiceStore.OpenIncident;
import dev.homeops.monitoring.api.MonitoredServiceResponse;
import dev.homeops.notification.config.IncidentNotificationProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class IncidentNotificationProducer {
    static final String OPENED = "INCIDENT_OPENED";
    static final String ESCALATED = "INCIDENT_ESCALATED";
    static final String RECOVERED = "INCIDENT_RECOVERED";

    private final NotificationOutbox outbox;
    private final Duration escalationAfter;

    public IncidentNotificationProducer(
            NotificationOutbox outbox,
            IncidentNotificationProperties properties) {
        this.outbox = outbox;
        this.escalationAfter = properties.escalationAfter();
    }

    public void recordOpened(
            UUID incidentId,
            MonitoredServiceResponse service,
            boolean notificationEnabled,
            Instant openedAt) {
        if (!notificationEnabled) {
            return;
        }
        outbox.enqueue(intent(
                incidentId,
                service,
                OPENED,
                severity(service.severity()),
                null,
                openedAt,
                openedAt,
                "Service incident opened",
                "A monitored service entered an incident state.",
                "OPEN"));
    }

    public void recordContinued(
            OpenIncident incident,
            MonitoredServiceResponse service,
            boolean notificationEnabled,
            Instant observedAt) {
        if (observedAt.isBefore(incident.openedAt().plus(escalationAfter))
                || !notificationEnabled) {
            return;
        }
        sentRoot(incident.id()).ifPresent(root -> outbox.enqueue(intent(
                incident.id(),
                service,
                ESCALATED,
                NotificationSeverity.CRITICAL,
                root.id(),
                incident.openedAt(),
                observedAt,
                "Service incident escalated",
                "A monitored service incident exceeded its escalation threshold.",
                "ESCALATED")));
    }

    public void recordRecovered(
            OpenIncident incident,
            MonitoredServiceResponse service,
            boolean notificationEnabled,
            Instant resolvedAt) {
        if (!notificationEnabled) {
            return;
        }
        sentRoot(incident.id()).ifPresent(root -> outbox.enqueue(intent(
                incident.id(),
                service,
                RECOVERED,
                NotificationSeverity.RECOVERY,
                root.id(),
                incident.openedAt(),
                resolvedAt,
                "Service incident recovered",
                "A monitored service incident recovered.",
                "RECOVERED")));
    }

    private Optional<NotificationEventReference> sentRoot(UUID incidentId) {
        return outbox.findEvent(NotificationSourceType.INCIDENT, incidentId, OPENED)
                .filter(root -> root.status() == NotificationStatus.SENT);
    }

    private NotificationIntent intent(
            UUID incidentId,
            MonitoredServiceResponse service,
            String eventCode,
            NotificationSeverity severity,
            UUID parentNotificationId,
            Instant openedAt,
            Instant occurredAt,
            String title,
            String summary,
            String lifecycleStatus) {
        List<NotificationField> fields = eventCode.equals(OPENED)
                ? List.of(
                        new NotificationField("Service", service.name(), true),
                        new NotificationField("Severity", service.severity(), true),
                        new NotificationField("Status", lifecycleStatus, true))
                : List.of(
                        new NotificationField("Service", service.name(), true),
                        new NotificationField("Severity", service.severity(), true),
                        new NotificationField("Status", lifecycleStatus, true),
                        new NotificationField("Duration", durationSummary(openedAt, occurredAt), true));
        return new NotificationIntent(
                NotificationSourceType.INCIDENT,
                incidentId,
                severity,
                eventCode,
                "incident:" + incidentId + ":" + eventCode,
                parentNotificationId,
                occurredAt,
                new NotificationPayload(eventCode, title, summary, fields, occurredAt));
    }

    private static NotificationSeverity severity(String value) {
        return switch (value) {
            case "INFO" -> NotificationSeverity.INFO;
            case "WARNING" -> NotificationSeverity.WARNING;
            case "CRITICAL" -> NotificationSeverity.CRITICAL;
            default -> throw new IllegalArgumentException("Incident severity is unsupported");
        };
    }

    private static String durationSummary(Instant openedAt, Instant eventAt) {
        long totalMinutes = Math.max(0, Duration.between(openedAt, eventAt).toMinutes());
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return hours == 0 ? totalMinutes + "m" : hours + "h " + minutes + "m";
    }
}
