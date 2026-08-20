package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homeops.agent.persistence.AgentStatusStore;
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
class ContainerNotificationMigrationPostgresqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    private PostgresqlNotificationTestDatabase database;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createSchema() {
        database = PostgresqlNotificationTestDatabase.create();
        jdbc = database.jdbc();
    }

    @AfterEach
    void dropSchema() {
        database.close();
    }

    @Test
    void should_migrateFromV8ToV9WithoutRewritingExistingDomainRows() {
        database.migrateTo("8");
        UUID snapshotId = UUID.randomUUID();
        AgentStatusStore previousApplicationStore = new AgentStatusStore(jdbc);
        assertThat(previousApplicationStore.insertIfAbsent(
                "local-mac", snapshotId, "v1", NOW.minusSeconds(1), NOW)).isTrue();
        jdbc.update("""
                INSERT INTO notification_event (
                    id, deduplication_key, canonical_deduplication_hash,
                    source_type, source_id, payload, channel, severity, event_type,
                    status, attempt_count, occurred_at, next_attempt_at,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'AGENT', ?, '{}'::jsonb, 'DISCORD', 'INFO',
                        'AGENT_VERSION_CHANGED', 'PENDING', 0, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), "sha256:" + "a".repeat(64), "a".repeat(64),
                snapshotId, Timestamp.from(NOW), Timestamp.from(NOW),
                Timestamp.from(NOW), Timestamp.from(NOW));

        Flyway flyway = database.migrateToCurrent();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("9");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_status", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_event", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM container_notification_state", Integer.class))
                .isZero();
        assertThat(previousApplicationStore.findForUpdate("local-mac"))
                .isPresent()
                .get()
                .extracting(AgentStatusStore.AgentStatusSnapshot::lastSnapshotId)
                .isEqualTo(snapshotId);
    }

    @Test
    void should_enforceBoundedIdentityEnumAndAuthorityConstraints() {
        database.migrateToCurrent();
        insertValidState(UUID.randomUUID(), "a".repeat(64), true,
                "RUNNING", "HEALTHY", null, null);

        assertThatThrownBy(() -> insertValidState(
                UUID.randomUUID(), "short", true,
                "RUNNING", "HEALTHY", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertValidState(
                UUID.randomUUID(), "b".repeat(64), true,
                "INVALID", "HEALTHY", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertValidState(
                UUID.randomUUID(), "c".repeat(64), false,
                "EXITED", "NONE", NOW.minusSeconds(1), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertValidState(
                UUID.randomUUID(), "a".repeat(64), true,
                "RUNNING", "HEALTHY", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname = 'uk_container_notification_agent_identity'
                """, Integer.class)).isEqualTo(1);
    }

    private void insertValidState(
            UUID id,
            String logicalHash,
            boolean allowed,
            String state,
            String health,
            Instant failureStartedAt,
            UUID activeEpisodeId) {
        jdbc.update("""
                INSERT INTO container_notification_state (
                    id, agent_id, logical_identity_hash, display_name, compose_project,
                    instance_fingerprint, notifications_allowed, state, health,
                    last_snapshot_id, last_captured_at, failure_started_at,
                    active_episode_id)
                VALUES (?, 'local-mac', ?, 'api', 'project', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                logicalHash,
                "f".repeat(64),
                allowed,
                state,
                health,
                UUID.randomUUID(),
                Timestamp.from(NOW),
                failureStartedAt == null ? null : Timestamp.from(failureStartedAt),
                activeEpisodeId);
    }
}
