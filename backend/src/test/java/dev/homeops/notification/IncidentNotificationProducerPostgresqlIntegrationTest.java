package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homeops.monitoring.HttpServiceChecker;
import dev.homeops.monitoring.MonitoredServiceStore;
import dev.homeops.monitoring.ServiceCheckCoordinator;
import dev.homeops.monitoring.api.MonitoredServiceResponse;
import dev.homeops.notification.config.HomeOpsNotificationProperties;
import dev.homeops.notification.config.IncidentNotificationProperties;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class IncidentNotificationProducerPostgresqlIntegrationTest {
    private static final Instant OPENED_AT = Instant.parse("2026-08-20T00:00:00Z");
    private static final Duration ESCALATION_AFTER = Duration.ofMinutes(15);
    private static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/"
            + "x".repeat(64);

    private static PostgresqlNotificationTestDatabase database;
    private static JdbcTemplate jdbc;
    private static MonitoredServiceStore monitoredServices;
    private static DataSourceTransactionManager transactionManager;
    private static TransactionTemplate transactions;
    private static NotificationPayloadCodec codec;

    @BeforeAll
    static void migrateAndCreateServices() {
        database = PostgresqlNotificationTestDatabase.create();
        database.migrateToCurrent();
        jdbc = database.jdbc();
        monitoredServices = new MonitoredServiceStore(jdbc);
        transactionManager = new DataSourceTransactionManager(database.dataSource());
        transactions = new TransactionTemplate(transactionManager);
        codec = new NotificationPayloadCodec(new ObjectMapper(), 8_192);
    }

    @AfterAll
    static void dropSchema() {
        database.close();
    }

    @BeforeEach
    void clearRows() {
        jdbc.update("DELETE FROM notification_event");
        jdbc.update("DELETE FROM incident");
        jdbc.update("DELETE FROM health_check_result");
        jdbc.update("DELETE FROM monitored_service");
    }

    @Test
    void should_atomicallyPersistSuppressedRootForActualOpenWinner_when_notificationsAreDisabled() {
        MonitoredServiceResponse service = createService(true, 1, 1, "WARNING");

        record(service, down(), OPENED_AT, false, codec);

        UUID incidentId = openIncidentId(service.id());
        NotificationRow root = notification("INCIDENT_OPENED");
        assertThat(root.sourceType()).isEqualTo("INCIDENT");
        assertThat(root.sourceId()).isEqualTo(incidentId);
        assertThat(root.parentId()).isNull();
        assertThat(root.severity()).isEqualTo("WARNING");
        assertThat(root.status()).isEqualTo("SUPPRESSED");
        assertThat(healthResultCount(service.id())).isEqualTo(1);
    }

    @Test
    void should_rollBackCheckAndIncident_when_openedRootEncodingFails() {
        MonitoredServiceResponse service = createService(true, 1, 1, "CRITICAL");
        NotificationPayloadCodec failingCodec = new NotificationPayloadCodec(new ObjectMapper(), 1);

        assertThatThrownBy(() -> record(service, down(), OPENED_AT, false, failingCodec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification payload exceeds the serialized byte limit");

        assertThat(healthResultCount(service.id())).isZero();
        assertThat(incidentCount(service.id())).isZero();
        assertThat(notificationCount()).isZero();
    }

    @Test
    void should_createOneIncidentAndRoot_when_openChecksRace() throws Exception {
        MonitoredServiceResponse service = createService(true, 1, 1, "INFO");

        raceRecords(service, down(), OPENED_AT, false, codec);

        assertThat(incidentCount(service.id())).isEqualTo(1);
        assertThat(notificationCount("INCIDENT_OPENED")).isEqualTo(1);
        assertThat(notification("INCIDENT_OPENED").sourceId()).isEqualTo(openIncidentId(service.id()));
    }

    @Test
    void should_notReplayMissingRoot_when_authorityIsEnabledAfterIncidentOpened() {
        MonitoredServiceResponse service = createService(false, 1, 1, "WARNING");
        record(service, down(), OPENED_AT, true, codec);
        jdbc.update("UPDATE monitored_service SET notification_enabled = TRUE WHERE id = ?", service.id());

        record(service, down(), OPENED_AT.plus(ESCALATION_AFTER), true, codec);
        record(service, healthy(), OPENED_AT.plus(Duration.ofMinutes(16)), true, codec);

        assertThat(notificationCount()).isZero();
        assertThat(resolvedIncidentCount(service.id())).isEqualTo(1);
    }

    @Test
    void should_createOneEscalationAtThresholdAndPreserveParent_when_downObservationsRepeat() {
        MonitoredServiceResponse service = createService(true, 1, 1, "WARNING");
        record(service, down(), OPENED_AT, true, codec);
        UUID rootId = makeRootSent();

        record(service, down(), OPENED_AT.plus(ESCALATION_AFTER).minusNanos(1), true, codec);
        assertThat(notificationCount("INCIDENT_ESCALATED")).isZero();

        record(service, down(), OPENED_AT.plus(ESCALATION_AFTER), true, codec);
        record(service, down(), OPENED_AT.plus(Duration.ofMinutes(16)), true, codec);

        assertThat(notificationCount("INCIDENT_ESCALATED")).isEqualTo(1);
        NotificationRow escalation = notification("INCIDENT_ESCALATED");
        assertThat(escalation.parentId()).isEqualTo(rootId);
        assertThat(escalation.severity()).isEqualTo("CRITICAL");
        assertThat(escalation.status()).isEqualTo("PENDING");
    }

    @Test
    void should_createOneEscalation_when_thresholdObservationsRace() throws Exception {
        MonitoredServiceResponse service = createService(true, 1, 1, "CRITICAL");
        record(service, down(), OPENED_AT, true, codec);
        UUID rootId = makeRootSent();

        raceRecords(service, down(), OPENED_AT.plus(ESCALATION_AFTER), true, codec);

        assertThat(notificationCount("INCIDENT_ESCALATED")).isEqualTo(1);
        assertThat(notification("INCIDENT_ESCALATED").parentId()).isEqualTo(rootId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "DELIVERING", "FAILED", "DELIVERY_UNKNOWN", "SUPPRESSED"})
    void should_notCreateEscalationOrRecovery_when_rootIsNotSent(String rootStatus) {
        MonitoredServiceResponse service = createService(true, 1, 1, "WARNING");
        record(service, down(), OPENED_AT, true, codec);
        setRootStatus(rootStatus);

        record(service, down(), OPENED_AT.plus(ESCALATION_AFTER), true, codec);
        record(service, healthy(), OPENED_AT.plus(Duration.ofMinutes(16)), true, codec);

        assertThat(notificationEventTypes()).containsExactly("INCIDENT_OPENED");
        assertThat(resolvedIncidentCount(service.id())).isEqualTo(1);
    }

    @Test
    void should_reEvaluateEscalation_when_pendingRootLaterBecomesSent() {
        MonitoredServiceResponse service = createService(true, 1, 1, "WARNING");
        record(service, down(), OPENED_AT, true, codec);

        record(service, down(), OPENED_AT.plus(ESCALATION_AFTER), true, codec);
        assertThat(notificationCount("INCIDENT_ESCALATED")).isZero();

        UUID rootId = makeRootSent();
        record(service, down(), OPENED_AT.plus(Duration.ofMinutes(16)), true, codec);

        assertThat(notification("INCIDENT_ESCALATED").parentId()).isEqualTo(rootId);
    }

    @Test
    void should_atomicallyResolveAndPersistRecovery_when_sentRootExists() {
        MonitoredServiceResponse service = createService(true, 1, 1, "CRITICAL");
        record(service, down(), OPENED_AT, true, codec);
        UUID rootId = makeRootSent();
        Instant recoveredAt = OPENED_AT.plus(Duration.ofMinutes(10));

        record(service, healthy(), recoveredAt, true, codec);

        assertThat(resolvedIncidentCount(service.id())).isEqualTo(1);
        NotificationRow recovery = notification("INCIDENT_RECOVERED");
        assertThat(recovery.parentId()).isEqualTo(rootId);
        assertThat(recovery.severity()).isEqualTo("RECOVERY");
        assertThat(recovery.status()).isEqualTo("PENDING");
        assertThat(recovery.occurredAt()).isEqualTo(recoveredAt);
    }

    @Test
    void should_rollBackResolutionAndHealthyResult_when_recoveryEncodingFails() {
        MonitoredServiceResponse service = createService(true, 1, 1, "CRITICAL");
        record(service, down(), OPENED_AT, true, codec);
        makeRootSent();
        NotificationPayloadCodec failingCodec = new NotificationPayloadCodec(new ObjectMapper(), 1);

        assertThatThrownBy(() -> record(
                service,
                healthy(),
                OPENED_AT.plus(Duration.ofMinutes(10)),
                true,
                failingCodec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification payload exceeds the serialized byte limit");

        assertThat(openIncidentCount(service.id())).isEqualTo(1);
        assertThat(healthResultCount(service.id())).isEqualTo(1);
        assertThat(notificationCount("INCIDENT_RECOVERED")).isZero();
    }

    @Test
    void should_createOneRecovery_when_resolutionChecksRace() throws Exception {
        MonitoredServiceResponse service = createService(true, 1, 1, "INFO");
        record(service, down(), OPENED_AT, true, codec);
        UUID rootId = makeRootSent();

        raceRecords(service, healthy(), OPENED_AT.plus(Duration.ofMinutes(10)), true, codec);

        assertThat(resolvedIncidentCount(service.id())).isEqualTo(1);
        assertThat(notificationCount("INCIDENT_RECOVERED")).isEqualTo(1);
        assertThat(notification("INCIDENT_RECOVERED").parentId()).isEqualTo(rootId);
    }

    @Test
    void should_persistOnlyAllowlistedIncidentPayload_when_checkContainsPrivateMetadata() {
        MonitoredServiceResponse service = createService(true, 1, 1, "WARNING");

        record(service, new HttpServiceChecker.Result(
                false, 599, 3_000, "SyntheticPrivateTimeout"), OPENED_AT, false, codec);

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM notification_event WHERE event_type = 'INCIDENT_OPENED'",
                String.class);
        assertThat(payload)
                .contains("logical-service", "WARNING", "OPEN")
                .doesNotContain(
                        service.url(),
                        "private-origin",
                        "SyntheticPrivateTimeout",
                        "599",
                        "credential",
                        "token");
    }

    @Test
    void should_keepSuppressedRootChildlessThroughEscalationAndRecovery_when_notificationsAreDisabled() {
        MonitoredServiceResponse service = createService(true, 1, 1, "WARNING");
        record(service, down(), OPENED_AT, false, codec);

        record(service, down(), OPENED_AT.plus(ESCALATION_AFTER), false, codec);
        record(service, healthy(), OPENED_AT.plus(Duration.ofMinutes(16)), false, codec);

        assertThat(notificationEventTypes()).containsExactly("INCIDENT_OPENED");
        assertThat(notification("INCIDENT_OPENED").status()).isEqualTo("SUPPRESSED");
    }

    private void record(
            MonitoredServiceResponse service,
            HttpServiceChecker.Result result,
            Instant checkedAt,
            boolean notificationsEnabled,
            NotificationPayloadCodec payloadCodec) {
        transactions.executeWithoutResult(status -> coordinatorAt(
                checkedAt, notificationsEnabled, payloadCodec).record(service, result));
    }

    private ServiceCheckCoordinator coordinatorAt(
            Instant now,
            boolean notificationsEnabled,
            NotificationPayloadCodec payloadCodec) {
        NotificationOutboxTransactions outboxTransactions = new NotificationOutboxTransactions(
                new NotificationOutboxStore(jdbc),
                payloadCodec,
                properties(notificationsEnabled),
                transactionManager,
                Clock.fixed(now, ZoneOffset.UTC));
        IncidentNotificationProducer producer = new IncidentNotificationProducer(
                new NotificationOutbox(outboxTransactions),
                new IncidentNotificationProperties(ESCALATION_AFTER));
        return new ServiceCheckCoordinator(
                monitoredServices, producer, Clock.fixed(now, ZoneOffset.UTC));
    }

    private void raceRecords(
            MonitoredServiceResponse service,
            HttpServiceChecker.Result result,
            Instant checkedAt,
            boolean notificationsEnabled,
            NotificationPayloadCodec payloadCodec) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = List.of(
                    executor.submit(() -> recordWhenReleased(
                            service, result, checkedAt, notificationsEnabled, payloadCodec, ready, start)),
                    executor.submit(() -> recordWhenReleased(
                            service, result, checkedAt, notificationsEnabled, payloadCodec, ready, start)));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            futures.forEach(this::await);
        }
    }

    private Void recordWhenReleased(
            MonitoredServiceResponse service,
            HttpServiceChecker.Result result,
            Instant checkedAt,
            boolean notificationsEnabled,
            NotificationPayloadCodec payloadCodec,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        record(service, result, checkedAt, notificationsEnabled, payloadCodec);
        return null;
    }

    private void await(Future<Void> future) {
        try {
            future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent incident notification transaction did not complete", exception);
        }
    }

    private MonitoredServiceResponse createService(
            boolean notificationEnabled,
            int failureThreshold,
            int recoveryThreshold,
            String severity) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO monitored_service
                    (id, name, url, http_method, expected_status, timeout_ms, interval_seconds,
                     failure_threshold, recovery_threshold, severity, enabled, notification_enabled)
                VALUES (?, ?, ?, 'GET', 200, 3000, 30, ?, ?, ?, TRUE, ?)
                """, id, "logical-service", "https://private-origin.example.invalid/health",
                failureThreshold, recoveryThreshold, severity, notificationEnabled);
        return new MonitoredServiceResponse(
                id,
                "logical-service",
                "https://private-origin.example.invalid/health",
                "GET",
                200,
                3_000,
                30,
                failureThreshold,
                recoveryThreshold,
                severity,
                true,
                notificationEnabled);
    }

    private UUID makeRootSent() {
        UUID id = notification("INCIDENT_OPENED").id();
        jdbc.update("""
                UPDATE notification_event
                SET status = 'SENT', sent_at = ?, next_attempt_at = NULL,
                    lease_token = NULL, lease_expires_at = NULL,
                    terminal_at = ?, updated_at = ?
                WHERE id = ?
                """, java.sql.Timestamp.from(OPENED_AT), java.sql.Timestamp.from(OPENED_AT),
                java.sql.Timestamp.from(OPENED_AT), id);
        return id;
    }

    private void setRootStatus(String status) {
        UUID id = notification("INCIDENT_OPENED").id();
        Instant at = OPENED_AT.plusSeconds(1);
        if (status.equals("PENDING")) {
            return;
        }
        if (status.equals("DELIVERING")) {
            jdbc.update("""
                    UPDATE notification_event
                    SET status = 'DELIVERING', next_attempt_at = NULL,
                        lease_token = ?, lease_expires_at = ?, terminal_at = NULL, updated_at = ?
                    WHERE id = ?
                    """, UUID.randomUUID(), java.sql.Timestamp.from(at.plusSeconds(30)),
                    java.sql.Timestamp.from(at), id);
            return;
        }
        jdbc.update("""
                UPDATE notification_event
                SET status = ?, next_attempt_at = NULL,
                    lease_token = NULL, lease_expires_at = NULL,
                    failure_code = CASE WHEN ? = 'SUPPRESSED' THEN 'NOTIFICATIONS_DISABLED' ELSE 'TEST_TERMINAL' END,
                    terminal_at = ?, updated_at = ?
                WHERE id = ?
                """, status, status, java.sql.Timestamp.from(at), java.sql.Timestamp.from(at), id);
    }

    private UUID openIncidentId(UUID serviceId) {
        return jdbc.queryForObject("""
                SELECT id FROM incident
                WHERE service_id = ? AND status IN ('OPEN', 'ACKNOWLEDGED')
                """, UUID.class, serviceId);
    }

    private int notificationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM notification_event", Integer.class);
    }

    private int notificationCount(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notification_event WHERE event_type = ?",
                Integer.class,
                eventType);
    }

    private int incidentCount(UUID serviceId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM incident WHERE service_id = ?", Integer.class, serviceId);
    }

    private int openIncidentCount(UUID serviceId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM incident
                WHERE service_id = ? AND status IN ('OPEN', 'ACKNOWLEDGED')
                """, Integer.class, serviceId);
    }

    private int resolvedIncidentCount(UUID serviceId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM incident WHERE service_id = ? AND status = 'RESOLVED'",
                Integer.class,
                serviceId);
    }

    private int healthResultCount(UUID serviceId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM health_check_result WHERE service_id = ?",
                Integer.class,
                serviceId);
    }

    private NotificationRow notification(String eventType) {
        return jdbc.queryForObject("""
                SELECT id, source_type, source_id, parent_notification_id,
                       severity, status, occurred_at
                FROM notification_event
                WHERE event_type = ?
                """, (row, index) -> new NotificationRow(
                row.getObject("id", UUID.class),
                row.getString("source_type"),
                row.getObject("source_id", UUID.class),
                row.getObject("parent_notification_id", UUID.class),
                row.getString("severity"),
                row.getString("status"),
                row.getTimestamp("occurred_at").toInstant()), eventType);
    }

    private List<String> notificationEventTypes() {
        return jdbc.queryForList(
                "SELECT event_type FROM notification_event ORDER BY occurred_at, event_type",
                String.class);
    }

    private static HttpServiceChecker.Result down() {
        return new HttpServiceChecker.Result(false, 503, 20, "SyntheticDown");
    }

    private static HttpServiceChecker.Result healthy() {
        return new HttpServiceChecker.Result(true, 200, 15, null);
    }

    private static HomeOpsNotificationProperties properties(boolean enabled) {
        return new HomeOpsNotificationProperties(
                enabled, enabled ? WEBHOOK : null,
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofSeconds(1), 10, 6,
                Duration.ofSeconds(5), Duration.ofMinutes(15),
                65_536, 8_192, Duration.ofDays(30), Duration.ofDays(90));
    }

    private record NotificationRow(
            UUID id,
            String sourceType,
            UUID sourceId,
            UUID parentId,
            String severity,
            String status,
            Instant occurredAt) { }
}
