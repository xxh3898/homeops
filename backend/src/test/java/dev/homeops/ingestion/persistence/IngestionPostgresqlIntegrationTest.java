package dev.homeops.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.homeops.common.EventKeyConflictException;
import dev.homeops.common.InvalidIngestionStateTransitionException;
import dev.homeops.ingestion.IngestionDigest;
import dev.homeops.ingestion.IngestionService;
import dev.homeops.ingestion.api.BackupIngestionRequest;
import dev.homeops.ingestion.api.DeploymentIngestionRequest;
import dev.homeops.ingestion.api.IngestionAcceptedResponse;
import dev.homeops.notification.DeploymentNotificationProducer;
import dev.homeops.notification.BackupNotificationProducer;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class IngestionPostgresqlIntegrationTest {
    private static final Instant STARTED_RAW = Instant.parse("2026-01-01T00:00:00.123456789Z");
    private static final Instant STARTED_CANONICAL = Instant.parse("2026-01-01T00:00:00.123457Z");
    private static final Instant FINISHED_CARRY_RAW = Instant.parse("2026-01-01T00:00:00.999999500Z");
    private static final Instant FINISHED_CARRY_CANONICAL = Instant.parse("2026-01-01T00:00:01Z");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static IngestionService service;

    @BeforeAll
    static void migrateAndCreateService() {
        var dataSource = new DriverManagerDataSource(
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_URL"),
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_USERNAME"),
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_PASSWORD"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        service = new IngestionService(new DeploymentIngestionStore(jdbc), new BackupIngestionStore(jdbc),
                new IngestionEventKeyLedgerStore(jdbc), new IngestionDigest(), mock(DeploymentNotificationProducer.class),
                mock(BackupNotificationProducer.class));
    }

    @BeforeEach
    void clearIngestionTables() {
        jdbc.update("DELETE FROM deployment");
        jdbc.update("DELETE FROM backup_run");
        jdbc.update("DELETE FROM ingestion_event_key_ledger");
    }

    @Test
    void should_reserveIndependentLedgerRows_when_sourcesUseSameLiteralEventKey() {
        String eventKey = "shared-source-key";

        IngestionAcceptedResponse deployment = inTransaction(() -> service.acceptDeployment(deployment(
                eventKey, DeploymentIngestionRequest.DeploymentStatus.RUNNING, STARTED_RAW, null)));
        IngestionAcceptedResponse backup = inTransaction(() -> service.acceptBackup(backup(
                eventKey, BackupIngestionRequest.BackupStatus.RUNNING, STARTED_RAW, null, null, null)));

        assertThat(deployment.id()).isNotEqualTo(backup.id());
        assertThat(jdbc.queryForList("""
                SELECT source_type, event_key
                FROM ingestion_event_key_ledger
                ORDER BY source_type
                """))
                .containsExactly(
                        java.util.Map.of("source_type", "BACKUP", "event_key", eventKey),
                        java.util.Map.of("source_type", "DEPLOYMENT", "event_key", eventKey));
    }

    @Test
    void should_rejectDeploymentReplay_when_businessHistoryWasDeleted() {
        DeploymentIngestionRequest request = deployment(
                "deleted-deployment", DeploymentIngestionRequest.DeploymentStatus.RUNNING, STARTED_RAW, null);
        inTransaction(() -> service.acceptDeployment(request));
        jdbc.update("DELETE FROM deployment WHERE event_key = ?", request.eventKey());

        assertThatThrownBy(() -> inTransaction(() -> service.acceptDeployment(request)))
                .isInstanceOf(EventKeyConflictException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM deployment WHERE event_key = ?", Integer.class, request.eventKey())).isZero();
        assertThat(ledgerCount("DEPLOYMENT", request.eventKey())).isOne();
    }

    @Test
    void should_rejectBackupReplay_when_businessHistoryWasDeleted() {
        BackupIngestionRequest request = backup(
                "deleted-backup", BackupIngestionRequest.BackupStatus.RUNNING, STARTED_RAW, null, null, null);
        inTransaction(() -> service.acceptBackup(request));
        jdbc.update("DELETE FROM backup_run WHERE event_key = ?", request.eventKey());

        assertThatThrownBy(() -> inTransaction(() -> service.acceptBackup(request)))
                .isInstanceOf(EventKeyConflictException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM backup_run WHERE event_key = ?", Integer.class, request.eventKey())).isZero();
        assertThat(ledgerCount("BACKUP", request.eventKey())).isOne();
    }

    @Test
    void should_persistCanonicalDeploymentTimestampAndAcceptEquivalentRetries() {
        DeploymentIngestionRequest raw = deployment("deploy-precision", DeploymentIngestionRequest.DeploymentStatus.RUNNING,
                Instant.parse("2026-01-01T00:00:00.123456499Z"), null);
        IngestionAcceptedResponse accepted = inTransaction(() -> service.acceptDeployment(raw));

        assertThat(deploymentStartedAt(raw.eventKey())).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
        assertThat(inTransaction(() -> service.acceptDeployment(raw)))
                .isEqualTo(new IngestionAcceptedResponse(accepted.id(), true));
        assertThat(inTransaction(() -> service.acceptDeployment(deployment(raw.eventKey(),
                DeploymentIngestionRequest.DeploymentStatus.RUNNING,
                Instant.parse("2026-01-01T00:00:00.123456000Z"), null))))
                .isEqualTo(new IngestionAcceptedResponse(accepted.id(), true));
    }

    @ParameterizedTest
    @EnumSource(value = DeploymentIngestionRequest.DeploymentStatus.class, names = {"SUCCESS", "FAILED"})
    void should_transitionDeploymentWithCanonicalLifecycleTimestamp(
            DeploymentIngestionRequest.DeploymentStatus terminalStatus) {
        String eventKey = "deploy-transition-" + terminalStatus;
        inTransaction(() -> service.acceptDeployment(deployment(eventKey,
                DeploymentIngestionRequest.DeploymentStatus.RUNNING, STARTED_RAW, null)));

        DeploymentIngestionRequest terminal = deployment(eventKey, terminalStatus, STARTED_RAW, FINISHED_CARRY_RAW);
        IngestionAcceptedResponse result = inTransaction(() -> service.acceptDeployment(terminal));

        assertThat(result.duplicate()).isFalse();
        assertThat(deploymentStartedAt(eventKey)).isEqualTo(STARTED_CANONICAL);
        assertThat(deploymentFinishedAt(eventKey)).isEqualTo(FINISHED_CARRY_CANONICAL);
        assertThat(deploymentStatus(eventKey)).isEqualTo(terminalStatus.name());
        assertThat(inTransaction(() -> service.acceptDeployment(terminal)))
                .isEqualTo(new IngestionAcceptedResponse(result.id(), true));
    }

    @Test
    void should_rejectDeploymentWhenLifecycleDiffersAfterCanonicalization() {
        inTransaction(() -> service.acceptDeployment(deployment("deploy-conflict",
                DeploymentIngestionRequest.DeploymentStatus.RUNNING, STARTED_RAW, null)));
        DeploymentIngestionRequest conflicting = new DeploymentIngestionRequest("deploy-conflict", "another-project",
                "production", "main", "0123456789012345678901234567890123456789", "sha-0123456", null,
                DeploymentIngestionRequest.DeploymentStatus.SUCCESS, STARTED_RAW, FINISHED_CARRY_RAW,
                null, null, "github-actions", "123", null, false);

        assertThatThrownBy(() -> inTransaction(() -> service.acceptDeployment(conflicting)))
                .isInstanceOf(EventKeyConflictException.class);
    }

    @Test
    void should_allowOnlyOneDeploymentTerminalUpdateDuringConcurrentRace() throws Exception {
        String eventKey = "deploy-race";
        inTransaction(() -> service.acceptDeployment(deployment(eventKey,
                DeploymentIngestionRequest.DeploymentStatus.RUNNING, STARTED_RAW, null)));

        List<String> outcomes = runDeploymentTerminalRace(eventKey);

        assertThat(outcomes).containsExactlyInAnyOrder("accepted", "rejected");
        assertThat(deploymentStatus(eventKey)).isIn("SUCCESS", "FAILED");
    }

    @Test
    void should_persistCanonicalBackupTimestampsAndAcceptEquivalentRetries() {
        BackupIngestionRequest raw = backup("backup-precision", BackupIngestionRequest.BackupStatus.RUNNING,
                Instant.parse("2026-01-01T00:00:00.123456499Z"), null,
                Instant.parse("2026-01-01T00:00:00.123456499Z"),
                Instant.parse("2026-01-01T00:00:00.123456500Z"));
        IngestionAcceptedResponse accepted = inTransaction(() -> service.acceptBackup(raw));

        assertThat(backupTimes(raw.eventKey())).isEqualTo(new BackupTimes(
                Instant.parse("2026-01-01T00:00:00.123456Z"), null,
                Instant.parse("2026-01-01T00:00:00.123456Z"),
                Instant.parse("2026-01-01T00:00:00.123457Z")));
        assertThat(inTransaction(() -> service.acceptBackup(raw)))
                .isEqualTo(new IngestionAcceptedResponse(accepted.id(), true));
        assertThat(inTransaction(() -> service.acceptBackup(backup(raw.eventKey(),
                BackupIngestionRequest.BackupStatus.RUNNING,
                Instant.parse("2026-01-01T00:00:00.123456000Z"), null,
                Instant.parse("2026-01-01T00:00:00.123456000Z"),
                Instant.parse("2026-01-01T00:00:00.123457000Z")))))
                .isEqualTo(new IngestionAcceptedResponse(accepted.id(), true));
    }

    @ParameterizedTest
    @EnumSource(value = BackupIngestionRequest.BackupStatus.class, names = {"SUCCESS", "FAILED"})
    void should_transitionBackupWithCanonicalLifecycleTimestamp(BackupIngestionRequest.BackupStatus terminalStatus) {
        String eventKey = "backup-transition-" + terminalStatus;
        inTransaction(() -> service.acceptBackup(backup(eventKey, BackupIngestionRequest.BackupStatus.RUNNING,
                STARTED_RAW, null, null, null)));

        BackupIngestionRequest terminal = backup(eventKey, terminalStatus, STARTED_RAW, FINISHED_CARRY_RAW,
                Instant.parse("2026-01-02T00:00:00.123456789Z"),
                Instant.parse("2026-01-03T00:00:00.123456500Z"));
        IngestionAcceptedResponse result = inTransaction(() -> service.acceptBackup(terminal));

        assertThat(result.duplicate()).isFalse();
        assertThat(backupTimes(eventKey)).isEqualTo(new BackupTimes(STARTED_CANONICAL,
                FINISHED_CARRY_CANONICAL, Instant.parse("2026-01-02T00:00:00.123457Z"),
                Instant.parse("2026-01-03T00:00:00.123457Z")));
        assertThat(inTransaction(() -> service.acceptBackup(terminal)))
                .isEqualTo(new IngestionAcceptedResponse(result.id(), true));
    }

    @Test
    void should_rejectBackupWhenLifecycleDiffersAfterCanonicalization() {
        inTransaction(() -> service.acceptBackup(backup("backup-conflict", BackupIngestionRequest.BackupStatus.RUNNING,
                STARTED_RAW, null, null, null)));
        BackupIngestionRequest conflicting = new BackupIngestionRequest("backup-conflict", "another-project",
                "POSTGRESQL", "backups/backup.dump", BackupIngestionRequest.BackupStatus.SUCCESS, STARTED_RAW,
                FINISHED_CARRY_RAW, 1024L, null, null, null, null);

        assertThatThrownBy(() -> inTransaction(() -> service.acceptBackup(conflicting)))
                .isInstanceOf(EventKeyConflictException.class);
    }

    @Test
    void should_allowOnlyOneBackupTerminalUpdateDuringConcurrentRace() throws Exception {
        String eventKey = "backup-race";
        inTransaction(() -> service.acceptBackup(backup(eventKey, BackupIngestionRequest.BackupStatus.RUNNING,
                STARTED_RAW, null, null, null)));

        List<String> outcomes = runBackupTerminalRace(eventKey);

        assertThat(outcomes).containsExactlyInAnyOrder("accepted", "rejected");
        assertThat(backupStatus(eventKey)).isIn("SUCCESS", "FAILED");
    }

    private List<String> runDeploymentTerminalRace(String eventKey) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = List.of(
                    executor.submit(() -> terminalDeploymentOutcome(eventKey,
                            DeploymentIngestionRequest.DeploymentStatus.SUCCESS, ready, start)),
                    executor.submit(() -> terminalDeploymentOutcome(eventKey,
                            DeploymentIngestionRequest.DeploymentStatus.FAILED, ready, start)));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return results.stream().map(this::futureResult).toList();
        }
    }

    private List<String> runBackupTerminalRace(String eventKey) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = List.of(
                    executor.submit(() -> terminalBackupOutcome(eventKey, BackupIngestionRequest.BackupStatus.SUCCESS,
                            ready, start)),
                    executor.submit(() -> terminalBackupOutcome(eventKey, BackupIngestionRequest.BackupStatus.FAILED,
                            ready, start)));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return results.stream().map(this::futureResult).toList();
        }
    }

    private String terminalDeploymentOutcome(String eventKey, DeploymentIngestionRequest.DeploymentStatus status,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try {
            inTransaction(() -> service.acceptDeployment(deployment(eventKey, status, STARTED_RAW, FINISHED_CARRY_RAW)));
            return "accepted";
        } catch (InvalidIngestionStateTransitionException exception) {
            return "rejected";
        }
    }

    private String terminalBackupOutcome(String eventKey, BackupIngestionRequest.BackupStatus status,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try {
            inTransaction(() -> service.acceptBackup(backup(eventKey, status, STARTED_RAW, FINISHED_CARRY_RAW,
                    null, null)));
            return "accepted";
        } catch (InvalidIngestionStateTransitionException exception) {
            return "rejected";
        }
    }

    private String futureResult(Future<String> result) {
        try {
            return result.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent ingestion did not complete", exception);
        }
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    private Instant deploymentStartedAt(String eventKey) {
        return jdbc.queryForObject("SELECT started_at FROM deployment WHERE event_key = ?",
                (result, row) -> result.getTimestamp(1).toInstant(), eventKey);
    }

    private Instant deploymentFinishedAt(String eventKey) {
        return jdbc.queryForObject("SELECT finished_at FROM deployment WHERE event_key = ?",
                (result, row) -> result.getTimestamp(1).toInstant(), eventKey);
    }

    private String deploymentStatus(String eventKey) {
        return jdbc.queryForObject("SELECT status FROM deployment WHERE event_key = ?", String.class, eventKey);
    }

    private BackupTimes backupTimes(String eventKey) {
        return jdbc.queryForObject("SELECT started_at, finished_at, expires_at, restore_tested_at FROM backup_run WHERE event_key = ?",
                (result, row) -> new BackupTimes(result.getTimestamp(1).toInstant(), instant(result, 2),
                        instant(result, 3), instant(result, 4)), eventKey);
    }

    private String backupStatus(String eventKey) {
        return jdbc.queryForObject("SELECT status FROM backup_run WHERE event_key = ?", String.class, eventKey);
    }

    private int ledgerCount(String sourceType, String eventKey) {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM ingestion_event_key_ledger
                WHERE source_type = ? AND event_key = ?
                """, Integer.class, sourceType, eventKey);
    }

    private static Instant instant(java.sql.ResultSet result, int index) throws java.sql.SQLException {
        java.sql.Timestamp timestamp = result.getTimestamp(index);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static DeploymentIngestionRequest deployment(String eventKey,
            DeploymentIngestionRequest.DeploymentStatus status, Instant startedAt, Instant finishedAt) {
        return new DeploymentIngestionRequest(eventKey, "homeops", "production", "main",
                "0123456789012345678901234567890123456789", "sha-0123456", null, status, startedAt, finishedAt,
                null, null, "github-actions", "123", null, false);
    }

    private static BackupIngestionRequest backup(String eventKey, BackupIngestionRequest.BackupStatus status,
            Instant startedAt, Instant finishedAt, Instant expiresAt, Instant restoreTestedAt) {
        return new BackupIngestionRequest(eventKey, "homeops", "POSTGRESQL", "backups/backup.dump", status,
                startedAt, finishedAt, 1024L, expiresAt, null, restoreTestedAt, null);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured for this test");
        return value;
    }

    private record BackupTimes(Instant startedAt, Instant finishedAt, Instant expiresAt, Instant restoreTestedAt) { }
}
