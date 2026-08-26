package dev.homeops.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class IngestionEventKeyLedgerMigrationPostgresqlIntegrationTest {
    private static final Instant STARTED_AT = Instant.parse("2026-08-26T00:00:00Z");

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
    void should_enforceMinimalSourceScopedLedgerContract_when_schemaIsMigratedCleanly() {
        Flyway flyway = database.migrateToCurrent();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");
        assertThat(jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'ingestion_event_key_ledger'
                ORDER BY ordinal_position
                """, String.class)).containsExactly("source_type", "event_key");
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_constraint constraint_row
                JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
                JOIN pg_namespace namespace_row ON namespace_row.oid = table_row.relnamespace
                WHERE namespace_row.nspname = current_schema()
                  AND table_row.relname = 'ingestion_event_key_ledger'
                  AND constraint_row.contype = 'f'
                """, Integer.class)).isZero();

        insertLedger("DEPLOYMENT", "shared-event-key");
        insertLedger("BACKUP", "shared-event-key");

        assertThat(ledgerRows()).containsExactly(
                Map.of("source_type", "BACKUP", "event_key", "shared-event-key"),
                Map.of("source_type", "DEPLOYMENT", "event_key", "shared-event-key"));
        assertThatThrownBy(() -> insertLedger("DEPLOYMENT", "shared-event-key"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLedger("OTHER", "other-event-key"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLedger("BACKUP", "x".repeat(129)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void should_backfillBothNamespaces_when_migratingFromV1ToV12() {
        database.migrateTo("1");
        insertV1Deployment("shared-key", "RUNNING");
        insertV1Deployment("deployment-only", "SUCCESS");
        insertV1Backup("shared-key", "RUNNING");
        insertV1Backup("backup-only", "FAILED");

        Flyway flyway = database.migrateToCurrent();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");
        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
        assertThat(ledgerRows()).containsExactly(
                Map.of("source_type", "BACKUP", "event_key", "backup-only"),
                Map.of("source_type", "BACKUP", "event_key", "shared-key"),
                Map.of("source_type", "DEPLOYMENT", "event_key", "deployment-only"),
                Map.of("source_type", "DEPLOYMENT", "event_key", "shared-key"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployment", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM backup_run", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList(
                "SELECT event_key, status FROM deployment ORDER BY event_key"))
                .containsExactly(
                        Map.of("event_key", "deployment-only", "status", "SUCCESS"),
                        Map.of("event_key", "shared-key", "status", "RUNNING"));
        assertThat(jdbc.queryForList(
                "SELECT event_key, status FROM backup_run ORDER BY event_key"))
                .containsExactly(
                        Map.of("event_key", "backup-only", "status", "FAILED"),
                        Map.of("event_key", "shared-key", "status", "RUNNING"));
    }

    @Test
    void should_preserveCurrentBusinessRowsAndLedger_when_migratingFromV11ToV12() {
        database.migrateTo("11");
        insertCurrentDeployment("deployment-current", "RUNNING", "a".repeat(64));
        insertCurrentBackup("backup-current", "SUCCESS", "b".repeat(64));
        List<Map<String, Object>> deploymentBefore = deploymentShape();
        List<Map<String, Object>> backupBefore = backupShape();

        Flyway flyway = database.migrateToCurrent();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");
        assertThat(deploymentShape()).isEqualTo(deploymentBefore);
        assertThat(backupShape()).isEqualTo(backupBefore);
        assertThat(ledgerRows()).containsExactly(
                Map.of("source_type", "BACKUP", "event_key", "backup-current"),
                Map.of("source_type", "DEPLOYMENT", "event_key", "deployment-current"));

        jdbc.update("DELETE FROM deployment");
        jdbc.update("DELETE FROM backup_run");

        assertThat(ledgerRows()).hasSize(2);
    }

    private void insertLedger(String sourceType, String eventKey) {
        jdbc.update("""
                INSERT INTO ingestion_event_key_ledger (source_type, event_key)
                VALUES (?, ?)
                """, sourceType, eventKey);
    }

    private void insertV1Deployment(String eventKey, String status) {
        jdbc.update("""
                INSERT INTO deployment (
                    id, event_key, project, environment, commit_sha, status, started_at)
                VALUES (?, ?, 'homeops', 'production', ?, ?, ?)
                """, UUID.randomUUID(), eventKey, "a".repeat(40), status, Timestamp.from(STARTED_AT));
    }

    private void insertV1Backup(String eventKey, String status) {
        jdbc.update("""
                INSERT INTO backup_run (
                    id, event_key, project, database_type, status, started_at)
                VALUES (?, ?, 'homeops', 'POSTGRESQL', ?, ?)
                """, UUID.randomUUID(), eventKey, status, Timestamp.from(STARTED_AT));
    }

    private void insertCurrentDeployment(String eventKey, String status, String digest) {
        jdbc.update("""
                INSERT INTO deployment (
                    id, event_key, project, environment, commit_sha, status, started_at, ingestion_digest)
                VALUES (?, ?, 'homeops', 'production', ?, ?, ?, ?)
                """, UUID.randomUUID(), eventKey, "a".repeat(40), status, Timestamp.from(STARTED_AT), digest);
    }

    private void insertCurrentBackup(String eventKey, String status, String digest) {
        jdbc.update("""
                INSERT INTO backup_run (
                    id, event_key, project, database_type, status, started_at, ingestion_digest)
                VALUES (?, ?, 'homeops', 'POSTGRESQL', ?, ?, ?)
                """, UUID.randomUUID(), eventKey, status, Timestamp.from(STARTED_AT), digest);
    }

    private List<Map<String, Object>> ledgerRows() {
        return jdbc.queryForList("""
                SELECT source_type, event_key
                FROM ingestion_event_key_ledger
                ORDER BY source_type, event_key
                """);
    }

    private List<Map<String, Object>> deploymentShape() {
        return jdbc.queryForList("""
                SELECT id, event_key, project, environment, commit_sha, status,
                       started_at, ingestion_digest, metadata
                FROM deployment
                ORDER BY event_key
                """);
    }

    private List<Map<String, Object>> backupShape() {
        return jdbc.queryForList("""
                SELECT id, event_key, project, database_type, status,
                       started_at, ingestion_digest, metadata
                FROM backup_run
                ORDER BY event_key
                """);
    }
}
