package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homeops.common.EventKeyConflictException;
import dev.homeops.common.InvalidIngestionStateTransitionException;
import dev.homeops.ingestion.IngestionDigest;
import dev.homeops.ingestion.IngestionService;
import dev.homeops.ingestion.api.BackupIngestionRequest;
import dev.homeops.ingestion.api.DeploymentIngestionRequest;
import dev.homeops.ingestion.api.IngestionAcceptedResponse;
import dev.homeops.ingestion.persistence.BackupIngestionStore;
import dev.homeops.ingestion.persistence.DeploymentIngestionStore;
import dev.homeops.notification.config.HomeOpsNotificationProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class BackupNotificationProducerPostgresqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-19T06:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-08-19T05:00:00.123456789Z");
    private static final Instant FINISHED_AT = Instant.parse("2026-08-19T05:05:00.999999500Z");
    private static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/"
            + "x".repeat(64);

    private static PostgresqlNotificationTestDatabase database;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactionManager;
    private static TransactionTemplate transactions;
    private static NotificationPayloadCodec codec;
    private static IngestionService disabledService;

    @BeforeAll
    static void migrateAndCreateService() {
        database = PostgresqlNotificationTestDatabase.create();
        database.migrateToCurrent();
        jdbc = database.jdbc();
        transactionManager = new DataSourceTransactionManager(database.dataSource());
        transactions = new TransactionTemplate(transactionManager);
        codec = new NotificationPayloadCodec(new ObjectMapper(), 8_192);
        disabledService = service(codec, false);
    }

    @AfterAll
    static void dropSchema() {
        database.close();
    }

    @BeforeEach
    void clearRows() {
        jdbc.update("DELETE FROM notification_event");
        jdbc.update("DELETE FROM deployment");
        jdbc.update("DELETE FROM backup_run");
        jdbc.update("DELETE FROM ingestion_event_key_ledger");
    }

    @Test
    void should_atomicallyPersistStartedIntentAsSuppressed_when_runningBackupIsInserted() {
        BackupIngestionRequest request = backup(
                "backup-started", BackupIngestionRequest.BackupStatus.RUNNING, null);

        IngestionAcceptedResponse accepted = inTransaction(() -> disabledService.acceptBackup(request));

        assertThat(accepted.duplicate()).isFalse();
        assertThat(notificationCount()).isEqualTo(1);
        NotificationRow row = notificationRow("BACKUP_STARTED");
        assertThat(row.sourceType()).isEqualTo("BACKUP");
        assertThat(row.sourceId()).isEqualTo(accepted.id());
        assertThat(row.severity()).isEqualTo("INFO");
        assertThat(row.status()).isEqualTo("SUPPRESSED");
        assertThat(row.occurredAt()).isEqualTo(canonical(STARTED_AT));
    }

    @ParameterizedTest
    @CsvSource({
        "SUCCESS, BACKUP_SUCCEEDED, INFO",
        "FAILED, BACKUP_FAILED, CRITICAL",
        "INCOMPLETE, BACKUP_INCOMPLETE, WARNING"
    })
    void should_persistOnlyTerminalIntent_when_firstBackupEventIsTerminal(
            BackupIngestionRequest.BackupStatus status,
            String eventType,
            String severity) {
        BackupIngestionRequest request = backup("backup-first-" + status, status, FINISHED_AT);

        IngestionAcceptedResponse accepted = inTransaction(() -> disabledService.acceptBackup(request));

        assertThat(notificationEventTypes()).containsExactly(eventType);
        NotificationRow row = notificationRow(eventType);
        assertThat(row.sourceType()).isEqualTo("BACKUP");
        assertThat(row.sourceId()).isEqualTo(accepted.id());
        assertThat(row.severity()).isEqualTo(severity);
        assertThat(row.occurredAt()).isEqualTo(canonical(FINISHED_AT));
    }

    @ParameterizedTest
    @CsvSource({
        "SUCCESS, BACKUP_SUCCEEDED, INFO",
        "FAILED, BACKUP_FAILED, CRITICAL",
        "INCOMPLETE, BACKUP_INCOMPLETE, WARNING"
    })
    void should_persistTerminalIntent_when_runningBackupTransitionWins(
            BackupIngestionRequest.BackupStatus status,
            String eventType,
            String severity) {
        String eventKey = "backup-transition-" + status;
        IngestionAcceptedResponse started = inTransaction(() -> disabledService.acceptBackup(backup(
                eventKey, BackupIngestionRequest.BackupStatus.RUNNING, null)));

        IngestionAcceptedResponse terminal = inTransaction(() -> disabledService.acceptBackup(backup(
                eventKey, status, FINISHED_AT)));

        assertThat(terminal.id()).isEqualTo(started.id());
        assertThat(terminal.duplicate()).isFalse();
        assertThat(notificationEventTypes())
                .containsExactlyInAnyOrder("BACKUP_STARTED", eventType);
        NotificationRow row = notificationRow(eventType);
        assertThat(row.sourceId()).isEqualTo(started.id());
        assertThat(row.severity()).isEqualTo(severity);
    }

    @Test
    void should_notCreateSecondIntent_when_initialBackupRequestIsReplayed() {
        BackupIngestionRequest request = backup(
                "backup-initial-replay", BackupIngestionRequest.BackupStatus.RUNNING, null);

        IngestionAcceptedResponse first = inTransaction(() -> disabledService.acceptBackup(request));
        IngestionAcceptedResponse replay = inTransaction(() -> disabledService.acceptBackup(request));

        assertThat(first.duplicate()).isFalse();
        assertThat(replay).isEqualTo(new IngestionAcceptedResponse(first.id(), true));
        assertThat(notificationEventTypes()).containsExactly("BACKUP_STARTED");
    }

    @Test
    void should_createOneTerminalIntent_when_terminalWinnerIsReplayed() {
        String eventKey = "backup-terminal-replay";
        inTransaction(() -> disabledService.acceptBackup(backup(
                eventKey, BackupIngestionRequest.BackupStatus.RUNNING, null)));
        BackupIngestionRequest terminal = backup(
                eventKey, BackupIngestionRequest.BackupStatus.SUCCESS, FINISHED_AT);

        IngestionAcceptedResponse first = inTransaction(() -> disabledService.acceptBackup(terminal));
        IngestionAcceptedResponse replay = inTransaction(() -> disabledService.acceptBackup(terminal));

        assertThat(first.duplicate()).isFalse();
        assertThat(replay).isEqualTo(new IngestionAcceptedResponse(first.id(), true));
        assertThat(notificationEventTypes())
                .containsExactlyInAnyOrder("BACKUP_STARTED", "BACKUP_SUCCEEDED");
    }

    @Test
    void should_rollBackBackupAndIntent_when_payloadEncodingFails() {
        IngestionService failingService = service(
                new NotificationPayloadCodec(new ObjectMapper(), 1), false);

        assertThatThrownBy(() -> inTransaction(() -> failingService.acceptBackup(backup(
                "backup-encoding-rollback", BackupIngestionRequest.BackupStatus.RUNNING, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification payload exceeds the serialized byte limit");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM backup_run", Integer.class)).isZero();
        assertThat(ledgerCount("BACKUP", "backup-encoding-rollback")).isZero();
        assertThat(notificationCount()).isZero();
    }

    @Test
    void should_allowRetryAfterLedgerReservationRollsBack_when_initialNotificationFails() {
        String eventKey = "backup-rollback-retry";
        BackupIngestionRequest request = backup(
                eventKey, BackupIngestionRequest.BackupStatus.RUNNING, null);
        IngestionService failingService = service(
                new NotificationPayloadCodec(new ObjectMapper(), 1), false);

        assertThatThrownBy(() -> inTransaction(() -> failingService.acceptBackup(request)))
                .isInstanceOf(IllegalArgumentException.class);

        IngestionAcceptedResponse retry = inTransaction(() -> disabledService.acceptBackup(request));

        assertThat(retry.duplicate()).isFalse();
        assertThat(ledgerCount("BACKUP", eventKey)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM backup_run WHERE event_key = ?", Integer.class, eventKey)).isOne();
        assertThat(notificationEventTypes()).containsExactly("BACKUP_STARTED");
    }

    @Test
    void should_rollBackTerminalTransition_when_outboxEnqueueFails() {
        String eventKey = "backup-transition-rollback";
        inTransaction(() -> disabledService.acceptBackup(backup(
                eventKey, BackupIngestionRequest.BackupStatus.RUNNING, null)));
        IngestionService failingService = service(
                new NotificationPayloadCodec(new ObjectMapper(), 1), false);

        assertThatThrownBy(() -> inTransaction(() -> failingService.acceptBackup(backup(
                eventKey, BackupIngestionRequest.BackupStatus.SUCCESS, FINISHED_AT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification payload exceeds the serialized byte limit");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM backup_run WHERE event_key = ?", String.class, eventKey))
                .isEqualTo("RUNNING");
        assertThat(notificationEventTypes()).containsExactly("BACKUP_STARTED");
    }

    @Test
    void should_rollBackBackupAndIntent_when_outerTransactionRollsBack() {
        transactions.executeWithoutResult(status -> {
            disabledService.acceptBackup(backup(
                    "backup-outer-rollback", BackupIngestionRequest.BackupStatus.RUNNING, null));
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM backup_run", Integer.class)).isZero();
        assertThat(ledgerCount("BACKUP", "backup-outer-rollback")).isZero();
        assertThat(notificationCount()).isZero();
    }

    @Test
    void should_createOneBackupAndOneIntent_when_identicalInitialRequestsRace() throws Exception {
        BackupIngestionRequest request = backup(
                "backup-insert-race", BackupIngestionRequest.BackupStatus.RUNNING, null);

        List<IngestionAcceptedResponse> responses = race(() -> disabledService.acceptBackup(request));

        assertThat(responses).extracting(IngestionAcceptedResponse::duplicate)
                .containsExactlyInAnyOrder(false, true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backup_run", Integer.class)).isEqualTo(1);
        assertThat(ledgerCount("BACKUP", request.eventKey())).isOne();
        assertThat(notificationEventTypes()).containsExactly("BACKUP_STARTED");
    }

    @Test
    void should_notResurrectBackupOrNotification_when_businessHistoryWasDeleted() {
        BackupIngestionRequest request = backup(
                "backup-deleted-history", BackupIngestionRequest.BackupStatus.RUNNING, null);
        inTransaction(() -> disabledService.acceptBackup(request));
        jdbc.update("DELETE FROM backup_run WHERE event_key = ?", request.eventKey());

        assertThatThrownBy(() -> inTransaction(() -> disabledService.acceptBackup(request)))
                .isInstanceOf(EventKeyConflictException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM backup_run WHERE event_key = ?", Integer.class, request.eventKey())).isZero();
        assertThat(ledgerCount("BACKUP", request.eventKey())).isOne();
        assertThat(notificationEventTypes()).containsExactly("BACKUP_STARTED");
    }

    @Test
    void should_createOneTerminalIntent_when_identicalTerminalRequestsRace() throws Exception {
        String eventKey = "backup-terminal-race";
        inTransaction(() -> disabledService.acceptBackup(backup(
                eventKey, BackupIngestionRequest.BackupStatus.RUNNING, null)));
        BackupIngestionRequest terminal = backup(
                eventKey, BackupIngestionRequest.BackupStatus.SUCCESS, FINISHED_AT);

        List<IngestionAcceptedResponse> responses = race(() -> disabledService.acceptBackup(terminal));

        assertThat(responses).extracting(IngestionAcceptedResponse::duplicate)
                .containsExactlyInAnyOrder(false, true);
        assertThat(notificationEventTypes())
                .containsExactlyInAnyOrder("BACKUP_STARTED", "BACKUP_SUCCEEDED");
    }

    @Test
    void should_createOnlyWinnerIntent_when_differentTerminalRequestsRace() throws Exception {
        String eventKey = "backup-competing-terminal-race";
        inTransaction(() -> disabledService.acceptBackup(backup(
                eventKey, BackupIngestionRequest.BackupStatus.RUNNING, null)));

        List<String> outcomes = raceCompetingTerminal(eventKey);

        assertThat(outcomes).containsExactlyInAnyOrder("accepted", "rejected");
        List<String> eventTypes = notificationEventTypes();
        assertThat(eventTypes).hasSize(2).contains("BACKUP_STARTED");
        assertThat(eventTypes.stream().filter(type -> !type.equals("BACKUP_STARTED")).toList())
                .singleElement()
                .isIn("BACKUP_SUCCEEDED", "BACKUP_FAILED");
    }

    @Test
    void should_persistOnlyAllowlistedPayload_when_ingestionContainsPrivateMetadata() {
        BackupIngestionRequest request = new BackupIngestionRequest(
                "private-backup-event-key",
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

        inTransaction(() -> disabledService.acceptBackup(request));

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM notification_event", String.class);
        assertThat(payload)
                .contains("Project", "homeops", "Database", "POSTGRESQL", "Status", "FAILED")
                .doesNotContain(
                        request.eventKey(), request.logicalLocation(), request.failureSummary(),
                        request.restoreTestStatus(), request.sizeBytes().toString(),
                        request.expiresAt().toString(), request.restoreTestedAt().toString());
    }

    @Test
    void should_notReplaySuppressedBackupIntent_when_notificationsAreLaterEnabled() {
        inTransaction(() -> disabledService.acceptBackup(backup(
                "backup-no-replay", BackupIngestionRequest.BackupStatus.RUNNING, null)));

        NotificationOutboxTransactions enabled = outboxTransactions(codec, true);

        assertThat(enabled.claimNext()).isEmpty();
        assertThat(notificationRow("BACKUP_STARTED").status()).isEqualTo("SUPPRESSED");
    }

    @Test
    void should_preserveDeploymentProducerResult_when_backupProducerIsConnected() {
        DeploymentIngestionRequest request = new DeploymentIngestionRequest(
                "deployment-regression-39",
                "homeops",
                "production",
                "main",
                "0123456789abcdef0123456789abcdef01234567",
                "sha-0123456",
                null,
                DeploymentIngestionRequest.DeploymentStatus.RUNNING,
                STARTED_AT,
                null,
                null,
                null,
                "github-actions",
                "39",
                null,
                false);

        inTransaction(() -> disabledService.acceptDeployment(request));

        assertThat(notificationEventTypes()).containsExactly("DEPLOYMENT_STARTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backup_run", Integer.class)).isZero();
    }

    private static IngestionService service(NotificationPayloadCodec payloadCodec, boolean enabled) {
        NotificationOutboxTransactions outboxTransactions = outboxTransactions(payloadCodec, enabled);
        NotificationOutbox outbox = new NotificationOutbox(outboxTransactions);
        return new IngestionService(
                new DeploymentIngestionStore(jdbc),
                new BackupIngestionStore(jdbc),
                new IngestionDigest(),
                new DeploymentNotificationProducer(outbox),
                new BackupNotificationProducer(outbox));
    }

    private static NotificationOutboxTransactions outboxTransactions(
            NotificationPayloadCodec payloadCodec,
            boolean enabled) {
        return new NotificationOutboxTransactions(
                new NotificationOutboxStore(jdbc),
                payloadCodec,
                properties(enabled),
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HomeOpsNotificationProperties properties(boolean enabled) {
        return new HomeOpsNotificationProperties(
                enabled, enabled ? WEBHOOK : null,
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofSeconds(1), 10, 6,
                Duration.ofSeconds(5), Duration.ofMinutes(15),
                65_536, 8_192, Duration.ofDays(30), Duration.ofDays(90));
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    private List<IngestionAcceptedResponse> race(
            java.util.function.Supplier<IngestionAcceptedResponse> action) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<IngestionAcceptedResponse>> results = List.of(
                    executor.submit(() -> concurrentAction(action, ready, start)),
                    executor.submit(() -> concurrentAction(action, ready, start)));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return results.stream().map(this::future).toList();
        }
    }

    private IngestionAcceptedResponse concurrentAction(
            java.util.function.Supplier<IngestionAcceptedResponse> action,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return inTransaction(action);
    }

    private List<String> raceCompetingTerminal(String eventKey) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = List.of(
                    executor.submit(() -> terminalOutcome(eventKey,
                            BackupIngestionRequest.BackupStatus.SUCCESS, ready, start)),
                    executor.submit(() -> terminalOutcome(eventKey,
                            BackupIngestionRequest.BackupStatus.FAILED, ready, start)));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return results.stream().map(this::future).toList();
        }
    }

    private String terminalOutcome(
            String eventKey,
            BackupIngestionRequest.BackupStatus status,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try {
            inTransaction(() -> disabledService.acceptBackup(backup(eventKey, status, FINISHED_AT)));
            return "accepted";
        } catch (InvalidIngestionStateTransitionException exception) {
            return "rejected";
        }
    }

    private <T> T future(Future<T> result) {
        try {
            return result.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent backup ingestion did not complete", exception);
        }
    }

    private int notificationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM notification_event", Integer.class);
    }

    private int ledgerCount(String sourceType, String eventKey) {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM ingestion_event_key_ledger
                WHERE source_type = ? AND event_key = ?
                """, Integer.class, sourceType, eventKey);
    }

    private List<String> notificationEventTypes() {
        return jdbc.queryForList(
                "SELECT event_type FROM notification_event ORDER BY occurred_at, event_type", String.class);
    }

    private NotificationRow notificationRow(String eventType) {
        return jdbc.queryForObject("""
                SELECT source_type, source_id, severity, status, occurred_at
                FROM notification_event
                WHERE event_type = ?
                """, (row, index) -> new NotificationRow(
                        row.getString("source_type"),
                        row.getObject("source_id", UUID.class),
                        row.getString("severity"),
                        row.getString("status"),
                        row.getTimestamp("occurred_at").toInstant()), eventType);
    }

    private static Instant canonical(Instant instant) {
        return dev.homeops.common.PostgresqlTimestamp.canonicalize(instant);
    }

    private static BackupIngestionRequest backup(
            String eventKey,
            BackupIngestionRequest.BackupStatus status,
            Instant finishedAt) {
        return new BackupIngestionRequest(
                eventKey,
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

    private record NotificationRow(
            String sourceType,
            UUID sourceId,
            String severity,
            String status,
            Instant occurredAt) { }
}
