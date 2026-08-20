package dev.homeops.notification;

import dev.homeops.ingestion.api.BackupIngestionRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class BackupNotificationProducer {
    private final NotificationOutbox outbox;

    BackupNotificationProducer(NotificationOutbox outbox) {
        this.outbox = outbox;
    }

    public void recordInitial(UUID backupId, BackupIngestionRequest request) {
        BackupEvent event = switch (request.status()) {
            case RUNNING -> BackupEvent.STARTED;
            case SUCCESS -> BackupEvent.SUCCEEDED;
            case FAILED -> BackupEvent.FAILED;
            case INCOMPLETE -> BackupEvent.INCOMPLETE;
        };
        enqueue(backupId, request, event);
    }

    public void recordTransition(UUID backupId, BackupIngestionRequest request) {
        BackupEvent event = switch (request.status()) {
            case SUCCESS -> BackupEvent.SUCCEEDED;
            case FAILED -> BackupEvent.FAILED;
            case INCOMPLETE -> BackupEvent.INCOMPLETE;
            case RUNNING -> null;
        };
        if (event != null) {
            enqueue(backupId, request, event);
        }
    }

    private void enqueue(
            UUID backupId,
            BackupIngestionRequest request,
            BackupEvent event) {
        Instant occurredAt = event == BackupEvent.STARTED || request.finishedAt() == null
                ? request.startedAt() : request.finishedAt();
        outbox.enqueue(new NotificationIntent(
                NotificationSourceType.BACKUP,
                backupId,
                event.severity,
                event.code,
                "backup:" + backupId + ":" + event.code,
                null,
                occurredAt,
                new NotificationPayload(
                        event.code,
                        event.title,
                        event.summary,
                        List.of(
                                new NotificationField("Project", request.project(), true),
                                new NotificationField("Database", request.databaseType(), true),
                                new NotificationField("Status", request.status().name(), true)),
                        occurredAt)));
    }

    private enum BackupEvent {
        STARTED(
                "BACKUP_STARTED",
                NotificationSeverity.INFO,
                "Backup started",
                "A backup entered its active lifecycle."),
        SUCCEEDED(
                "BACKUP_SUCCEEDED",
                NotificationSeverity.INFO,
                "Backup succeeded",
                "A backup reached a successful terminal state."),
        FAILED(
                "BACKUP_FAILED",
                NotificationSeverity.CRITICAL,
                "Backup failed",
                "A backup reached a failed terminal state."),
        INCOMPLETE(
                "BACKUP_INCOMPLETE",
                NotificationSeverity.WARNING,
                "Backup incomplete",
                "A backup reached an incomplete terminal state.");

        private final String code;
        private final NotificationSeverity severity;
        private final String title;
        private final String summary;

        BackupEvent(String code, NotificationSeverity severity, String title, String summary) {
            this.code = code;
            this.severity = severity;
            this.title = title;
            this.summary = summary;
        }
    }
}
