package dev.homeops.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class AutomaticRecoveryDecisionPostgresqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final UUID SERVICE_ID = UUID.fromString(
            "10000000-0000-4000-8000-000000000119");

    private PostgresqlRecoveryTestDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void createSchema() {
        database = PostgresqlRecoveryTestDatabase.create();
        database.migrateToCurrent();
        jdbc = database.jdbc();
        transactionManager = new DataSourceTransactionManager(database.dataSource());
        insertService();
    }

    @AfterEach
    void dropSchema() {
        database.close();
    }

    @Test
    void should_createOneRecoveryReservation_when_mappedOpenIncidentIsEvaluatedConcurrently()
            throws Exception {
        UUID incidentId = insertIncident(NOW, "OPEN");
        insertMapping(true, "backend");
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<AutomaticRecoveryDecisionService.Decision>> decisions = List.of(
                    executor.submit(() -> evaluateAfter(start, incidentId, NOW)),
                    executor.submit(() -> evaluateAfter(start, incidentId, NOW)));
            start.countDown();
            AutomaticRecoveryDecisionService.Decision first = decisions.get(0).get();
            AutomaticRecoveryDecisionService.Decision second = decisions.get(1).get();

            assertThat(first.attempt().id()).isEqualTo(second.attempt().id());
            assertThat(List.of(first.dispatchEligible(), second.dispatchEligible()))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM automatic_recovery_attempt
                WHERE incident_id = ? AND status = 'REQUESTED'
                """, Integer.class, incidentId)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM automatic_recovery_mapping
                WHERE service_id = ? AND last_reserved_at = ?
                """, Integer.class, SERVICE_ID, Timestamp.from(NOW))).isOne();
    }

    @Test
    void should_skipWithoutMutation_when_mappingIsAbsentDisabledOrIncidentResolved() {
        UUID unmappedIncident = insertIncident(NOW, "OPEN");
        AutomaticRecoveryAttempt unmapped = service()
                .evaluateOpenIncident(unmappedIncident, SERVICE_ID, NOW)
                .attempt();
        assertThat(unmapped.status()).isEqualTo(AutomaticRecoveryStatus.SKIPPED);
        assertThat(unmapped.reasonCode()).isEqualTo(AutomaticRecoveryReasonCode.TARGET_UNMAPPED);
        assertThat(unmapped.project()).isNull();

        resolveIncident(unmappedIncident, NOW.plusSeconds(1));
        insertMapping(false, "rhaomi-web");
        UUID disabledIncident = insertIncident(NOW.plusSeconds(2), "OPEN");
        AutomaticRecoveryAttempt disabled = service()
                .evaluateOpenIncident(disabledIncident, SERVICE_ID, NOW.plusSeconds(2))
                .attempt();
        assertThat(disabled.status()).isEqualTo(AutomaticRecoveryStatus.SKIPPED);
        assertThat(disabled.reasonCode()).isEqualTo(AutomaticRecoveryReasonCode.AUTHORITY_DISABLED);

        resolveIncident(disabledIncident, NOW.plusSeconds(3));
        jdbc.update("UPDATE automatic_recovery_mapping SET enabled = TRUE WHERE service_id = ?", SERVICE_ID);
        UUID resolvedIncident = insertIncident(NOW.plusSeconds(4), "RESOLVED");
        AutomaticRecoveryAttempt resolved = service()
                .evaluateOpenIncident(resolvedIncident, SERVICE_ID, NOW.plusSeconds(4))
                .attempt();
        assertThat(resolved.status()).isEqualTo(AutomaticRecoveryStatus.SKIPPED);
        assertThat(resolved.reasonCode()).isEqualTo(AutomaticRecoveryReasonCode.INCIDENT_NOT_OPEN);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM automatic_recovery_attempt WHERE status = 'REQUESTED'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT last_reserved_at FROM automatic_recovery_mapping WHERE service_id = ?
                """, Timestamp.class, SERVICE_ID)).isNull();
    }

    @Test
    void should_keepCooldownAcrossServiceRestartAndAllowNewEpisode_when_boundaryArrives() {
        insertMapping(true, "backend");
        UUID firstIncident = insertIncident(NOW, "OPEN");
        AutomaticRecoveryDecisionService.Decision first = service()
                .evaluateOpenIncident(firstIncident, SERVICE_ID, NOW);
        assertThat(first.dispatchEligible()).isTrue();
        resolveIncident(firstIncident, NOW.plusSeconds(1));

        Instant insideCooldown = NOW.plus(AutomaticRecoveryDecisionService.COOLDOWN)
                .minusSeconds(1);
        UUID secondIncident = insertIncident(insideCooldown, "OPEN");
        AutomaticRecoveryDecisionService restartedService = service();
        AutomaticRecoveryAttempt skipped = restartedService
                .evaluateOpenIncident(secondIncident, SERVICE_ID, insideCooldown)
                .attempt();
        assertThat(skipped.status()).isEqualTo(AutomaticRecoveryStatus.SKIPPED);
        assertThat(skipped.reasonCode()).isEqualTo(AutomaticRecoveryReasonCode.COOLDOWN_ACTIVE);
        resolveIncident(secondIncident, insideCooldown.plusMillis(1));

        Instant boundary = NOW.plus(Duration.ofMinutes(30));
        UUID thirdIncident = insertIncident(boundary, "OPEN");
        AutomaticRecoveryDecisionService.Decision eligible = service()
                .evaluateOpenIncident(thirdIncident, SERVICE_ID, boundary);

        assertThat(eligible.dispatchEligible()).isTrue();
        assertThat(eligible.attempt().status()).isEqualTo(AutomaticRecoveryStatus.REQUESTED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM automatic_recovery_attempt WHERE status = 'REQUESTED'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT last_reserved_at FROM automatic_recovery_mapping WHERE service_id = ?
                """, Timestamp.class, SERVICE_ID).toInstant()).isEqualTo(boundary);
    }

    private AutomaticRecoveryDecisionService.Decision evaluateAfter(
            CountDownLatch start,
            UUID incidentId,
            Instant openedAt) throws InterruptedException {
        start.await();
        return service().evaluateOpenIncident(incidentId, SERVICE_ID, openedAt);
    }

    private AutomaticRecoveryDecisionService service() {
        return new AutomaticRecoveryDecisionService(
                new AutomaticRecoveryStore(database.jdbc()),
                transactionManager);
    }

    private void insertService() {
        jdbc.update("""
                INSERT INTO monitored_service (
                    id, name, url, http_method, expected_status, timeout_ms,
                    interval_seconds, failure_threshold, recovery_threshold,
                    severity, enabled, notification_enabled)
                VALUES (?, 'Synthetic Rhaomi', 'https://example.test/health', 'GET', 200,
                        3000, 30, 1, 1, 'CRITICAL', TRUE, FALSE)
                """, SERVICE_ID);
    }

    private void insertMapping(boolean enabled, String target) {
        jdbc.update("""
                INSERT INTO automatic_recovery_mapping (service_id, project, target, enabled)
                VALUES (?, 'rhaomi', ?, ?)
                """, SERVICE_ID, target, enabled);
    }

    private UUID insertIncident(Instant openedAt, String status) {
        UUID incidentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO incident (
                    id, service_id, incident_type, severity, status, title,
                    opened_at, last_observed_at, resolved_at)
                VALUES (?, ?, 'SERVICE_UNAVAILABLE', 'CRITICAL', ?,
                        'Synthetic service unavailable', ?, ?, ?)
                """,
                incidentId,
                SERVICE_ID,
                status,
                Timestamp.from(openedAt),
                Timestamp.from(openedAt),
                status.equals("RESOLVED") ? Timestamp.from(openedAt) : null);
        return incidentId;
    }

    private void resolveIncident(UUID incidentId, Instant resolvedAt) {
        jdbc.update("""
                UPDATE incident
                SET status = 'RESOLVED', resolved_at = ?, resolved_xid = pg_current_xact_id()
                WHERE id = ?
                """, Timestamp.from(resolvedAt), incidentId);
    }
}
