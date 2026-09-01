package dev.homeops.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class AutomaticRecoveryMigrationPostgresqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final UUID SERVICE_ID = UUID.fromString(
            "10000000-0000-4000-8000-000000000119");
    private static final UUID INCIDENT_ID = UUID.fromString(
            "20000000-0000-4000-8000-000000000119");

    private PostgresqlRecoveryTestDatabase database;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createSchema() {
        database = PostgresqlRecoveryTestDatabase.create();
        jdbc = database.jdbc();
    }

    @AfterEach
    void dropSchema() {
        database.close();
    }

    @Test
    void should_createDefaultNoneBoundedRecoverySchema_when_migratingCleanlyToV14() {
        Flyway flyway = database.migrateToCurrent();
        insertService();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("14");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM automatic_recovery_mapping", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM automatic_recovery_attempt", Integer.class)).isZero();

        jdbc.update("""
                INSERT INTO automatic_recovery_mapping (service_id, project, target)
                VALUES (?, 'rhaomi', 'backend')
                """, SERVICE_ID);

        assertThat(jdbc.queryForObject("""
                SELECT enabled FROM automatic_recovery_mapping WHERE service_id = ?
                """, Boolean.class, SERVICE_ID)).isFalse();
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE automatic_recovery_mapping SET project = 'other' WHERE service_id = ?
                """, SERVICE_ID)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE automatic_recovery_mapping SET target = 'arbitrary' WHERE service_id = ?
                """, SERVICE_ID)).isInstanceOf(DataIntegrityViolationException.class);

        insertIncident("OPEN");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO automatic_recovery_attempt (
                    id, incident_id, service_id, project, target, action,
                    status, requested_at)
                VALUES (?, ?, ?, 'rhaomi', 'backend', 'SHELL', 'REQUESTED', ?)
                """,
                UUID.randomUUID(),
                INCIDENT_ID,
                SERVICE_ID,
                Timestamp.from(NOW))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO automatic_recovery_attempt (
                    id, incident_id, service_id, project, target, action,
                    status, reason_code, requested_at, completed_at,
                    pre_health, post_health, restart_count)
                VALUES (?, ?, ?, 'rhaomi', 'backend', 'RESTART', 'SKIPPED',
                        'ARBITRARY_REASON', ?, ?, 'UNKNOWN', 'UNKNOWN', 0)
                """,
                UUID.randomUUID(),
                INCIDENT_ID,
                SERVICE_ID,
                Timestamp.from(NOW),
                Timestamp.from(NOW))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void should_preserveExistingMonitoringData_when_migratingFromV13ToV14() {
        database.migrateTo("13");
        insertService();
        insertIncident("OPEN");

        Flyway flyway = database.migrateToCurrent();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("14");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM monitored_service WHERE id = ?", Integer.class, SERVICE_ID))
                .isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM incident WHERE id = ?", Integer.class, INCIDENT_ID))
                .isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM automatic_recovery_mapping", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM automatic_recovery_attempt", Integer.class)).isZero();
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

    private void insertIncident(String status) {
        jdbc.update("""
                INSERT INTO incident (
                    id, service_id, incident_type, severity, status, title,
                    opened_at, last_observed_at)
                VALUES (?, ?, 'SERVICE_UNAVAILABLE', 'CRITICAL', ?,
                        'Synthetic service unavailable', ?, ?)
                """,
                INCIDENT_ID,
                SERVICE_ID,
                status,
                Timestamp.from(NOW),
                Timestamp.from(NOW));
    }
}
