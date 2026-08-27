package dev.homeops.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class SignalIngestionMigrationPostgresqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    private PostgresqlIngestionTestDatabase database;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createSchema() {
        database = PostgresqlIngestionTestDatabase.create();
        jdbc = database.jdbc();
    }

    @AfterEach
    void dropSchema() {
        database.close();
    }

    @Test
    void should_createBoundedSignalSchema_when_migratingCleanlyToV13() {
        Flyway flyway = database.migrateToCurrent();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("13");
        assertThat(jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name IN ('monitoring_signal_episode', 'monitoring_signal_event')
                ORDER BY table_name
                """, String.class)).containsExactly("monitoring_signal_episode", "monitoring_signal_event");
        assertThat(jdbc.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname IN (
                    'uk_monitoring_signal_project_type_active',
                    'uk_monitoring_signal_event_episode_status')
                ORDER BY indexname
                """, String.class)).containsExactly(
                        "uk_monitoring_signal_event_episode_status",
                        "uk_monitoring_signal_project_type_active");
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name IN ('monitoring_signal_episode', 'monitoring_signal_event')
                  AND data_type IN ('json', 'jsonb')
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_trigger trigger_row
                JOIN pg_class table_row ON table_row.oid = trigger_row.tgrelid
                JOIN pg_namespace namespace_row ON namespace_row.oid = table_row.relnamespace
                WHERE namespace_row.nspname = current_schema()
                  AND table_row.relname = 'monitoring_signal_event'
                  AND trigger_row.tgname = 'trg_monitoring_signal_event_reserve_ingestion_event_key'
                  AND NOT trigger_row.tgisinternal
                """, Integer.class)).isOne();
        jdbc.update("""
                INSERT INTO ingestion_event_key_ledger (source_type, event_key)
                VALUES ('SIGNAL', 'migration-signal-key')
                """);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ingestion_event_key_ledger
                WHERE source_type = 'SIGNAL' AND event_key = 'migration-signal-key'
                """, Integer.class)).isOne();
    }

    @Test
    void should_preserveExistingOperationalHistory_when_migratingFromV12ToV13() {
        database.migrateTo("12");
        UUID deploymentId = UUID.randomUUID();
        UUID backupId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO deployment
                    (id, event_key, project, environment, commit_sha, status, started_at, ingestion_digest)
                VALUES (?, 'existing-deployment', 'homeops', 'production', ?, 'RUNNING', ?, ?)
                """, deploymentId, "a".repeat(40), Timestamp.from(NOW), "b".repeat(64));
        jdbc.update("""
                INSERT INTO backup_run
                    (id, event_key, project, database_type, status, started_at, ingestion_digest)
                VALUES (?, 'existing-backup', 'homeops', 'POSTGRESQL', 'SUCCESS', ?, ?)
                """, backupId, Timestamp.from(NOW), "c".repeat(64));
        jdbc.update("""
                INSERT INTO incident
                    (id, incident_type, severity, status, title, opened_at, last_observed_at)
                VALUES (?, 'HEALTH_CHECK', 'WARNING', 'RESOLVED', 'Existing incident', ?, ?)
                """, incidentId, Timestamp.from(NOW), Timestamp.from(NOW));

        Flyway flyway = database.migrateToCurrent();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("13");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployment WHERE id = ?", Integer.class, deploymentId))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backup_run WHERE id = ?", Integer.class, backupId))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident WHERE id = ?", Integer.class, incidentId))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring_signal_episode", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring_signal_event", Integer.class)).isZero();
    }
}
