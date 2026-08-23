package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class NotificationOutboxMigrationPostgresqlIntegrationTest {
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
    void should_migrateFromV1ToCurrentAndPreserveLegacyDuplicateRows() {
        database.migrateTo("1");
        insertLegacy("legacy-duplicate", "PENDING");
        insertLegacy("legacy-duplicate", "FAILED");

        Flyway flyway = database.migrateToCurrent();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("11");
        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_event WHERE deduplication_key = 'legacy-duplicate'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_event
                WHERE deduplication_key = 'legacy-duplicate'
                  AND canonical_deduplication_hash IS NULL
                  AND source_type IS NULL
                  AND source_id IS NULL
                  AND payload IS NULL
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void should_migrateFromV6ToCurrentAndBackfillAuditAndTerminalTimestamps() {
        database.migrateTo("6");
        insertLegacy("legacy-sent", "SENT");

        Flyway flyway = database.migrateToCurrent();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("11");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification_event
                WHERE deduplication_key = 'legacy-sent'
                  AND created_at IS NOT NULL
                  AND updated_at IS NOT NULL
                  AND terminal_at IS NOT NULL
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void should_applyPartialDedupPayloadAndLeaseConstraints_when_foundationRowsAreInserted() {
        database.migrateToCurrent();
        String hash = "a".repeat(64);
        insertFoundation(UUID.randomUUID(), hash, "{}", "PENDING", null, null, null);

        assertThatThrownBy(() -> insertFoundation(
                UUID.randomUUID(), hash, "{}", "PENDING", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertFoundation(
                UUID.randomUUID(), "b".repeat(64),
                "{\"value\":\"" + "x".repeat(8_193) + "\"}",
                "PENDING", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertFoundation(
                UUID.randomUUID(), "c".repeat(64), "{}", "DELIVERING",
                null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname IN (
                      'uk_notification_channel_canonical_dedup',
                      'ix_notification_pending_due',
                      'ix_notification_delivering_lease',
                      'ix_notification_terminal_retention')
                """, Integer.class)).isEqualTo(4);
    }

    private void insertLegacy(String deduplicationKey, String status) {
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        jdbc.update("""
                INSERT INTO notification_event (
                    id, deduplication_key, channel, severity, event_type,
                    status, attempt_count, occurred_at, sent_at, next_attempt_at)
                VALUES (?, ?, 'DISCORD', 'WARNING', 'LEGACY_EVENT', ?, 0, ?, ?, ?)
                """,
                UUID.randomUUID(), deduplicationKey, status,
                java.sql.Timestamp.from(now),
                status.equals("SENT") ? java.sql.Timestamp.from(now) : null,
                status.equals("PENDING") ? java.sql.Timestamp.from(now) : null);
    }

    private void insertFoundation(
            UUID id,
            String hash,
            String payload,
            String status,
            UUID leaseToken,
            Instant leaseExpiresAt,
            Instant terminalAt) {
        Instant now = Instant.parse("2026-08-19T00:00:00Z");
        jdbc.update("""
                INSERT INTO notification_event (
                    id, deduplication_key, canonical_deduplication_hash,
                    source_type, source_id, payload, channel, severity, event_type,
                    status, attempt_count, occurred_at, next_attempt_at,
                    lease_token, lease_expires_at, created_at, updated_at, terminal_at)
                VALUES (?, ?, ?, 'DEPLOYMENT', ?, CAST(? AS jsonb),
                        'DISCORD', 'WARNING', 'TEST_EVENT', ?, 0, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, "sha256:" + hash, hash, UUID.randomUUID(), payload, status,
                java.sql.Timestamp.from(now),
                status.equals("PENDING") ? java.sql.Timestamp.from(now) : null,
                leaseToken,
                leaseExpiresAt == null ? null : java.sql.Timestamp.from(leaseExpiresAt),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
                terminalAt == null ? null : java.sql.Timestamp.from(terminalAt));
    }
}
