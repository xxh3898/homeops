package dev.homeops.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class AutomaticRecoveryDispatchPostgresqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final UUID SERVICE_ID = UUID.fromString(
            "10000000-0000-4000-8000-000000000119");

    private PostgresqlRecoveryTestDatabase database;
    private JdbcTemplate jdbc;
    private AutomaticRecoveryDecisionService decisions;
    private AutomaticRecoveryTransactions transactions;

    @BeforeEach
    void createSchema() {
        database = PostgresqlRecoveryTestDatabase.create();
        database.migrateToCurrent();
        jdbc = database.jdbc();
        var transactionManager = new DataSourceTransactionManager(database.dataSource());
        decisions = new AutomaticRecoveryDecisionService(
                new AutomaticRecoveryStore(jdbc), transactionManager);
        transactions = new AutomaticRecoveryTransactions(
                new AutomaticRecoveryStore(jdbc), transactionManager);
        insertServiceAndMapping();
    }

    @AfterEach
    void dropSchema() {
        database.close();
    }

    @Test
    void should_dispatchAndApplyOnce_when_requestAndCapabilityRemainEligible() {
        UUID incidentId = insertIncident(NOW, "OPEN");
        AutomaticRecoveryAttempt requested = decisions
                .evaluateOpenIncident(incidentId, SERVICE_ID, NOW)
                .attempt();

        AutomaticRecoveryAttempt dispatched = transactions
                .claimNext(NOW.plusSeconds(1), true)
                .orElseThrow();
        boolean completed = transactions.complete(dispatched.id(), new AutomaticRecoveryResult(
                AutomaticRecoveryResultStatus.APPLIED,
                AutomaticRecoveryReasonCode.RECOVERY_APPLIED,
                NOW.plusSeconds(2),
                NOW.plusSeconds(3),
                AutomaticRecoveryHealth.DOWN,
                AutomaticRecoveryHealth.UP,
                1));

        assertThat(dispatched.id()).isEqualTo(requested.id());
        assertThat(dispatched.status()).isEqualTo(AutomaticRecoveryStatus.DISPATCHED);
        assertThat(completed).isTrue();
        assertThat(transactions.complete(dispatched.id(), new AutomaticRecoveryResult(
                AutomaticRecoveryResultStatus.FAILED,
                AutomaticRecoveryReasonCode.RECOVERY_FAILED,
                NOW.plusSeconds(2),
                NOW.plusSeconds(4),
                AutomaticRecoveryHealth.DOWN,
                AutomaticRecoveryHealth.DOWN,
                1))).isFalse();
        assertThat(transactions.claimNext(NOW.plusSeconds(5), true)).isEmpty();
        assertThat(status(dispatched.id())).isEqualTo("APPLIED");
        assertThat(reason(dispatched.id())).isEqualTo("RECOVERY_APPLIED");
    }

    @Test
    void should_skipWithoutDispatch_when_capabilityIsUnavailable() {
        UUID incidentId = insertIncident(NOW, "OPEN");
        AutomaticRecoveryAttempt requested = decisions
                .evaluateOpenIncident(incidentId, SERVICE_ID, NOW)
                .attempt();

        assertThat(transactions.claimNext(NOW.plusSeconds(1), false)).isEmpty();
        assertThat(status(requested.id())).isEqualTo("SKIPPED");
        assertThat(reason(requested.id())).isEqualTo("CAPABILITY_UNAVAILABLE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM automatic_recovery_attempt
                WHERE id = ? AND dispatched_at IS NOT NULL
                """, Integer.class, requested.id())).isZero();
    }

    @Test
    void should_skipWithoutDispatch_when_incidentResolvedOrAuthorityDisabled() {
        UUID resolvedIncident = insertIncident(NOW, "OPEN");
        AutomaticRecoveryAttempt resolvedRequest = decisions
                .evaluateOpenIncident(resolvedIncident, SERVICE_ID, NOW)
                .attempt();
        resolveIncident(resolvedIncident, NOW.plusSeconds(1));

        assertThat(transactions.claimNext(NOW.plusSeconds(2), true)).isEmpty();
        assertThat(reason(resolvedRequest.id())).isEqualTo("INCIDENT_NOT_OPEN");

        jdbc.update("UPDATE automatic_recovery_mapping SET enabled = TRUE, last_reserved_at = NULL");
        UUID disabledIncident = insertIncident(NOW.plusSeconds(3), "OPEN");
        AutomaticRecoveryAttempt disabledRequest = decisions
                .evaluateOpenIncident(disabledIncident, SERVICE_ID, NOW.plusSeconds(3))
                .attempt();
        jdbc.update("UPDATE automatic_recovery_mapping SET enabled = FALSE");

        assertThat(transactions.claimNext(NOW.plusSeconds(4), true)).isEmpty();
        assertThat(reason(disabledRequest.id())).isEqualTo("AUTHORITY_DISABLED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM automatic_recovery_attempt WHERE dispatched_at IS NOT NULL
                """, Integer.class)).isZero();
    }

    @Test
    void should_expireStaleRequestWithoutDispatch_when_maximumAgeArrives() {
        UUID incidentId = insertIncident(NOW, "OPEN");
        AutomaticRecoveryAttempt requested = decisions
                .evaluateOpenIncident(incidentId, SERVICE_ID, NOW)
                .attempt();

        assertThat(transactions.claimNext(
                NOW.plus(AutomaticRecoveryTransactions.REQUEST_MAXIMUM_AGE), true)).isEmpty();
        assertThat(status(requested.id())).isEqualTo("EXPIRED");
        assertThat(reason(requested.id())).isEqualTo("REQUEST_EXPIRED");
    }

    @Test
    void should_notRetry_when_terminalOutcomeIsUnknown() {
        UUID incidentId = insertIncident(NOW, "OPEN");
        AutomaticRecoveryAttempt requested = decisions
                .evaluateOpenIncident(incidentId, SERVICE_ID, NOW)
                .attempt();
        transactions.claimNext(NOW.plusSeconds(1), true).orElseThrow();

        assertThat(transactions.complete(requested.id(), new AutomaticRecoveryResult(
                AutomaticRecoveryResultStatus.OUTCOME_UNKNOWN,
                AutomaticRecoveryReasonCode.RECOVERY_RESTART_UNCONFIRMED,
                NOW.plusSeconds(2),
                NOW.plusSeconds(3),
                AutomaticRecoveryHealth.DOWN,
                AutomaticRecoveryHealth.UNKNOWN,
                1))).isTrue();

        assertThat(status(requested.id())).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(transactions.claimNext(NOW.plusSeconds(4), true)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM automatic_recovery_attempt WHERE incident_id = ?
                """, Integer.class, incidentId)).isOne();
    }

    private String status(UUID attemptId) {
        return jdbc.queryForObject(
                "SELECT status FROM automatic_recovery_attempt WHERE id = ?",
                String.class,
                attemptId);
    }

    private String reason(UUID attemptId) {
        return jdbc.queryForObject(
                "SELECT reason_code FROM automatic_recovery_attempt WHERE id = ?",
                String.class,
                attemptId);
    }

    private void insertServiceAndMapping() {
        jdbc.update("""
                INSERT INTO monitored_service (
                    id, name, url, http_method, expected_status, timeout_ms,
                    interval_seconds, failure_threshold, recovery_threshold,
                    severity, enabled, notification_enabled)
                VALUES (?, 'Synthetic Rhaomi', 'https://example.test/health', 'GET', 200,
                        3000, 30, 1, 1, 'CRITICAL', TRUE, FALSE)
                """, SERVICE_ID);
        jdbc.update("""
                INSERT INTO automatic_recovery_mapping (service_id, project, target, enabled)
                VALUES (?, 'rhaomi', 'backend', TRUE)
                """, SERVICE_ID);
    }

    private UUID insertIncident(Instant openedAt, String status) {
        UUID incidentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO incident (
                    id, service_id, incident_type, severity, status, title,
                    opened_at, last_observed_at)
                VALUES (?, ?, 'SERVICE_UNAVAILABLE', 'CRITICAL', ?,
                        'Synthetic service unavailable', ?, ?)
                """,
                incidentId,
                SERVICE_ID,
                status,
                Timestamp.from(openedAt),
                Timestamp.from(openedAt));
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
