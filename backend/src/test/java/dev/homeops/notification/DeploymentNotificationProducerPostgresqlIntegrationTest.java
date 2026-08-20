package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class DeploymentNotificationProducerPostgresqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-19T04:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-08-19T03:00:00.123456789Z");
    private static final Instant FINISHED_AT = Instant.parse("2026-08-19T03:05:00.999999500Z");
    private static final String COMMIT_SHA = "0123456789abcdef0123456789abcdef01234567";
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
    }

    @Test
    void should_atomicallyPersistStartedIntentAsSuppressed_when_runningDeploymentIsInserted() {
        DeploymentIngestionRequest request = deployment(
                "deploy-started", DeploymentIngestionRequest.DeploymentStatus.RUNNING, null);

        IngestionAcceptedResponse accepted = inTransaction(() -> disabledService.acceptDeployment(request));

        assertThat(accepted.duplicate()).isFalse();
        assertThat(notificationCount()).isEqualTo(1);
        NotificationRow row = notificationRow("DEPLOYMENT_STARTED");
        assertThat(row.sourceType()).isEqualTo("DEPLOYMENT");
        assertThat(row.sourceId()).isEqualTo(accepted.id());
        assertThat(row.severity()).isEqualTo("INFO");
        assertThat(row.status()).isEqualTo("SUPPRESSED");
        assertThat(row.occurredAt()).isEqualTo(canonical(STARTED_AT));
    }

    @Test
    void should_persistOnlyTerminalIntent_when_firstDeploymentEventIsTerminal() {
        DeploymentIngestionRequest request = deployment(
                "deploy-first-terminal", DeploymentIngestionRequest.DeploymentStatus.FAILED, FINISHED_AT);

        inTransaction(() -> disabledService.acceptDeployment(request));

        assertThat(notificationEventTypes()).containsExactly("DEPLOYMENT_FAILED");
        NotificationRow row = notificationRow("DEPLOYMENT_FAILED");
        assertThat(row.severity()).isEqualTo("CRITICAL");
        assertThat(row.occurredAt()).isEqualTo(canonical(FINISHED_AT));
    }

    @Test
    void should_notCreateSecondIntent_when_existingRequestedDeploymentTransitionsToRunning() {
        String eventKey = "deploy-requested-running";
        inTransaction(() -> disabledService.acceptDeployment(deployment(
                eventKey, DeploymentIngestionRequest.DeploymentStatus.REQUESTED, null)));

        inTransaction(() -> disabledService.acceptDeployment(deployment(
                eventKey, DeploymentIngestionRequest.DeploymentStatus.RUNNING, null)));

        assertThat(notificationEventTypes()).containsExactly("DEPLOYMENT_STARTED");
    }

    @Test
    void should_createOneTerminalIntent_when_terminalWinnerIsReplayed() {
        String eventKey = "deploy-replay";
        inTransaction(() -> disabledService.acceptDeployment(deployment(
                eventKey, DeploymentIngestionRequest.DeploymentStatus.RUNNING, null)));
        DeploymentIngestionRequest terminal = deployment(
                eventKey, DeploymentIngestionRequest.DeploymentStatus.SUCCESS, FINISHED_AT);

        IngestionAcceptedResponse first = inTransaction(() -> disabledService.acceptDeployment(terminal));
        IngestionAcceptedResponse replay = inTransaction(() -> disabledService.acceptDeployment(terminal));

        assertThat(first.duplicate()).isFalse();
        assertThat(replay).isEqualTo(new IngestionAcceptedResponse(first.id(), true));
        assertThat(notificationEventTypes())
                .containsExactlyInAnyOrder("DEPLOYMENT_STARTED", "DEPLOYMENT_SUCCEEDED");
    }

    @Test
    void should_rollBackDeploymentAndIntent_when_payloadEncodingFails() {
        IngestionService failingService = service(
                new NotificationPayloadCodec(new ObjectMapper(), 1), false);

        assertThatThrownBy(() -> inTransaction(() -> failingService.acceptDeployment(deployment(
                "deploy-rollback", DeploymentIngestionRequest.DeploymentStatus.RUNNING, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification payload exceeds the serialized byte limit");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployment", Integer.class)).isZero();
        assertThat(notificationCount()).isZero();
    }

    @Test
    void should_rollBackDeploymentAndIntent_when_outerTransactionRollsBack() {
        transactions.executeWithoutResult(status -> {
            disabledService.acceptDeployment(deployment(
                    "deploy-outer-rollback", DeploymentIngestionRequest.DeploymentStatus.RUNNING, null));
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployment", Integer.class)).isZero();
        assertThat(notificationCount()).isZero();
    }

    @Test
    void should_createOneDeploymentAndOneIntent_when_identicalInitialRequestsRace() throws Exception {
        DeploymentIngestionRequest request = deployment(
                "deploy-insert-race", DeploymentIngestionRequest.DeploymentStatus.RUNNING, null);

        List<IngestionAcceptedResponse> responses = race(() -> disabledService.acceptDeployment(request));

        assertThat(responses).extracting(IngestionAcceptedResponse::duplicate)
                .containsExactlyInAnyOrder(false, true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployment", Integer.class)).isEqualTo(1);
        assertThat(notificationEventTypes()).containsExactly("DEPLOYMENT_STARTED");
    }

    @Test
    void should_createOneTerminalIntent_when_identicalTerminalRequestsRace() throws Exception {
        String eventKey = "deploy-terminal-race";
        inTransaction(() -> disabledService.acceptDeployment(deployment(
                eventKey, DeploymentIngestionRequest.DeploymentStatus.RUNNING, null)));
        DeploymentIngestionRequest terminal = deployment(
                eventKey, DeploymentIngestionRequest.DeploymentStatus.SUCCESS, FINISHED_AT);

        List<IngestionAcceptedResponse> responses = race(() -> disabledService.acceptDeployment(terminal));

        assertThat(responses).extracting(IngestionAcceptedResponse::duplicate)
                .containsExactlyInAnyOrder(false, true);
        assertThat(notificationEventTypes())
                .containsExactlyInAnyOrder("DEPLOYMENT_STARTED", "DEPLOYMENT_SUCCEEDED");
    }

    @Test
    void should_createOnlyWinnerIntent_when_differentTerminalRequestsRace() throws Exception {
        String eventKey = "deploy-competing-terminal-race";
        inTransaction(() -> disabledService.acceptDeployment(deployment(
                eventKey, DeploymentIngestionRequest.DeploymentStatus.RUNNING, null)));

        List<String> outcomes = raceCompetingTerminal(eventKey);

        assertThat(outcomes).containsExactlyInAnyOrder("accepted", "rejected");
        List<String> eventTypes = notificationEventTypes();
        assertThat(eventTypes).hasSize(2).contains("DEPLOYMENT_STARTED");
        assertThat(eventTypes.stream().filter(type -> !type.equals("DEPLOYMENT_STARTED")).toList())
                .singleElement()
                .isIn("DEPLOYMENT_SUCCEEDED", "DEPLOYMENT_FAILED");
    }

    @Test
    void should_persistOnlyAllowlistedPayload_when_ingestionContainsPrivateMetadata() {
        DeploymentIngestionRequest request = new DeploymentIngestionRequest(
                "private-event-key",
                "homeops",
                "production",
                "private-branch",
                COMMIT_SHA,
                "private-image-tag",
                "fedcba9876543210fedcba9876543210fedcba98",
                DeploymentIngestionRequest.DeploymentStatus.FAILED,
                STARTED_AT,
                FINISHED_AT,
                "private-failure-stage",
                "private-failure-summary",
                "private-actor",
                "private-run-id",
                "https://private.invalid/workflow",
                false);

        inTransaction(() -> disabledService.acceptDeployment(request));

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM notification_event", String.class);
        assertThat(payload)
                .contains("Project", "homeops", "Environment", "production", "Commit", "0123456789ab",
                        "Status", "FAILED")
                .doesNotContain(
                        request.eventKey(), request.branch(), request.commitSha(), request.imageTag(),
                        request.previousCommitSha(), request.failureStage(), request.failureSummary(),
                        request.actor(), request.workflowRunId(), request.workflowRunUrl());
    }

    @Test
    void should_notCreateNotification_when_backupIsAccepted() {
        BackupIngestionRequest request = new BackupIngestionRequest(
                "backup-37", "homeops", "POSTGRESQL", "backups/backup.dump",
                BackupIngestionRequest.BackupStatus.SUCCESS,
                STARTED_AT, FINISHED_AT, 1_024L, null, null, null, null);

        inTransaction(() -> disabledService.acceptBackup(request));

        assertThat(notificationCount()).isZero();
    }

    @Test
    void should_notReplaySuppressedDeploymentIntent_when_notificationsAreLaterEnabled() {
        inTransaction(() -> disabledService.acceptDeployment(deployment(
                "deploy-no-replay", DeploymentIngestionRequest.DeploymentStatus.RUNNING, null)));

        NotificationOutboxTransactions enabled = outboxTransactions(codec, true);

        assertThat(enabled.claimNext()).isEmpty();
        assertThat(notificationRow("DEPLOYMENT_STARTED").status()).isEqualTo("SUPPRESSED");
    }

    private static IngestionService service(NotificationPayloadCodec payloadCodec, boolean enabled) {
        NotificationOutboxTransactions outboxTransactions = outboxTransactions(payloadCodec, enabled);
        DeploymentNotificationProducer producer = new DeploymentNotificationProducer(
                new NotificationOutbox(outboxTransactions));
        return new IngestionService(
                new DeploymentIngestionStore(jdbc),
                new BackupIngestionStore(jdbc),
                new IngestionDigest(),
                producer,
                mock(BackupNotificationProducer.class));
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
                            DeploymentIngestionRequest.DeploymentStatus.SUCCESS, ready, start)),
                    executor.submit(() -> terminalOutcome(eventKey,
                            DeploymentIngestionRequest.DeploymentStatus.FAILED, ready, start)));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return results.stream().map(this::future).toList();
        }
    }

    private String terminalOutcome(
            String eventKey,
            DeploymentIngestionRequest.DeploymentStatus status,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try {
            inTransaction(() -> disabledService.acceptDeployment(deployment(eventKey, status, FINISHED_AT)));
            return "accepted";
        } catch (InvalidIngestionStateTransitionException exception) {
            return "rejected";
        }
    }

    private <T> T future(Future<T> result) {
        try {
            return result.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent deployment ingestion did not complete", exception);
        }
    }

    private int notificationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM notification_event", Integer.class);
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

    private static DeploymentIngestionRequest deployment(
            String eventKey,
            DeploymentIngestionRequest.DeploymentStatus status,
            Instant finishedAt) {
        return new DeploymentIngestionRequest(
                eventKey,
                "homeops",
                "production",
                "main",
                COMMIT_SHA,
                "sha-0123456",
                null,
                status,
                STARTED_AT,
                finishedAt,
                null,
                null,
                "github-actions",
                "37",
                null,
                false);
    }

    private record NotificationRow(
            String sourceType,
            UUID sourceId,
            String severity,
            String status,
            Instant occurredAt) { }
}
