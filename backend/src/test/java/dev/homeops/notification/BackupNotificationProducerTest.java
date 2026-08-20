package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.homeops.ingestion.api.BackupIngestionRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BackupNotificationProducerTest {
    private static final UUID BACKUP_ID = UUID.fromString("10000000-0000-0000-0000-000000000039");
    private static final Instant STARTED_AT = Instant.parse("2026-08-19T05:00:00Z");
    private static final Instant FINISHED_AT = Instant.parse("2026-08-19T05:05:00Z");

    @Mock private NotificationOutbox outbox;
    private BackupNotificationProducer producer;

    @BeforeEach
    void createProducer() {
        producer = new BackupNotificationProducer(outbox);
    }

    @ParameterizedTest
    @CsvSource({
        "RUNNING, BACKUP_STARTED, INFO",
        "SUCCESS, BACKUP_SUCCEEDED, INFO",
        "FAILED, BACKUP_FAILED, CRITICAL",
        "INCOMPLETE, BACKUP_INCOMPLETE, WARNING"
    })
    void should_mapFirstInsertToOneIntent_when_anyAcceptedStatusIsInitial(
            BackupIngestionRequest.BackupStatus status,
            String eventCode,
            NotificationSeverity severity) {
        producer.recordInitial(BACKUP_ID, backup(status));

        NotificationIntent intent = capturedIntent();
        assertThat(intent.sourceType()).isEqualTo(NotificationSourceType.BACKUP);
        assertThat(intent.sourceId()).isEqualTo(BACKUP_ID);
        assertThat(intent.eventType()).isEqualTo(eventCode);
        assertThat(intent.severity()).isEqualTo(severity);
        assertThat(intent.payload().eventCode()).isEqualTo(eventCode);
        assertThat(intent.deduplicationMaterial())
                .isEqualTo("backup:" + BACKUP_ID + ":" + eventCode);
        assertThat(intent.occurredAt()).isEqualTo(
                eventCode.equals("BACKUP_STARTED") ? STARTED_AT : FINISHED_AT);
    }

    @ParameterizedTest
    @CsvSource({
        "SUCCESS, BACKUP_SUCCEEDED, INFO",
        "FAILED, BACKUP_FAILED, CRITICAL",
        "INCOMPLETE, BACKUP_INCOMPLETE, WARNING"
    })
    void should_mapTerminalWinnerToIntent_when_transitionIsAccepted(
            BackupIngestionRequest.BackupStatus status,
            String eventCode,
            NotificationSeverity severity) {
        producer.recordTransition(BACKUP_ID, backup(status));

        NotificationIntent intent = capturedIntent();
        assertThat(intent.eventType()).isEqualTo(eventCode);
        assertThat(intent.severity()).isEqualTo(severity);
        assertThat(intent.occurredAt()).isEqualTo(FINISHED_AT);
    }

    @Test
    void should_notCreateIntent_when_existingBackupRemainsRunning() {
        producer.recordTransition(BACKUP_ID, backup(BackupIngestionRequest.BackupStatus.RUNNING));

        verify(outbox, never()).enqueue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void should_includeOnlyAllowlistedBoundedFields_when_requestContainsPrivateMetadata() {
        BackupIngestionRequest request = new BackupIngestionRequest(
                "private-event-key",
                "homeops",
                "POSTGRESQL",
                "private/location/backup.dump",
                BackupIngestionRequest.BackupStatus.FAILED,
                STARTED_AT,
                FINISHED_AT,
                987_654_321L,
                Instant.parse("2026-09-19T05:00:00Z"),
                "private-failure-summary",
                Instant.parse("2026-09-20T05:00:00Z"),
                "PRIVATE_RESTORE_STATUS");

        producer.recordInitial(BACKUP_ID, request);

        NotificationIntent intent = capturedIntent();
        assertThat(intent.payload().fields())
                .extracting(NotificationField::name)
                .containsExactly("Project", "Database", "Status");
        assertThat(intent.payload().fields())
                .extracting(NotificationField::value)
                .containsExactly("homeops", "POSTGRESQL", "FAILED")
                .doesNotContain(
                        request.eventKey(), request.logicalLocation(), request.failureSummary(),
                        request.restoreTestStatus(), request.sizeBytes().toString(),
                        request.expiresAt().toString(), request.restoreTestedAt().toString());
    }

    @Test
    void should_useStartedAt_when_initialTerminalEventHasNoFinishedAt() {
        BackupIngestionRequest request = backup(BackupIngestionRequest.BackupStatus.FAILED, null);

        producer.recordInitial(BACKUP_ID, request);

        assertThat(capturedIntent().occurredAt()).isEqualTo(STARTED_AT);
    }

    private NotificationIntent capturedIntent() {
        ArgumentCaptor<NotificationIntent> captor = ArgumentCaptor.forClass(NotificationIntent.class);
        verify(outbox).enqueue(captor.capture());
        return captor.getValue();
    }

    private static BackupIngestionRequest backup(BackupIngestionRequest.BackupStatus status) {
        return backup(status, FINISHED_AT);
    }

    private static BackupIngestionRequest backup(
            BackupIngestionRequest.BackupStatus status,
            Instant finishedAt) {
        return new BackupIngestionRequest(
                "backup-39",
                "homeops",
                "POSTGRESQL",
                "homeops/backup.dump",
                status,
                STARTED_AT,
                finishedAt,
                1_024L,
                null,
                null,
                null,
                null);
    }
}
