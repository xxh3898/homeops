package dev.homeops.agent.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class ContainerActionAuditMigrationPostgresqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-20T15:00:00Z");

    private PostgresqlContainerActionTestDatabase database;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createSchema() {
        database = PostgresqlContainerActionTestDatabase.create();
        jdbc = database.jdbc();
    }

    @AfterEach
    void dropSchema() {
        database.close();
    }

    @Test
    void should_preserveAndNormalizeLegacyRows_when_migratingFromV1ToV12() {
        database.migrateTo("1");
        insertLegacy("REQUESTED", "legacy-requested", "legacy-id");
        insertLegacy("SUCCESS", "legacy-success", "legacy-id");
        insertLegacy("FAILED", "legacy-failed", "legacy-id");
        insertLegacy("REJECTED", "legacy-rejected", "legacy-id");
        insertLegacy("TIMED_OUT", "legacy-timeout", "legacy-id");

        Flyway flyway = database.migrateToCurrent();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");
        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
        assertThat(statusesByKey()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "legacy-requested", "REQUESTED",
                "legacy-success", "APPLIED",
                "legacy-failed", "FAILED",
                "legacy-rejected", "DENIED",
                "legacy-timeout", "OUTCOME_UNKNOWN"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM container_action_audit WHERE container_id_prefix = 'legacy-id'",
                Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM container_action_audit
                WHERE result <> 'REQUESTED'
                  AND completed_at IS NOT NULL
                  AND reason_code LIKE 'LEGACY_%'
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM container_action_audit WHERE recorded_xid IS NOT NULL",
                Integer.class)).isEqualTo(5);
    }

    @Test
    void should_upgradeExistingV9SchemaWithoutRequiringLegacyIdentifierRewrite() {
        database.migrateTo("9");
        insertLegacy("REQUESTED", "v9-requested", "not-a-public-id");

        Flyway flyway = database.migrateToCurrent();
        ContainerActionAuditTransactions audit = transactionsAt(NOW);
        ContainerActionReservation current = audit.reserve(
                UUID.randomUUID().toString(),
                "admin@example.test",
                "0123456789ab",
                ContainerControlOperation.START);
        ContainerActionAuditTransactions later = transactionsAt(
                NOW.plus(ContainerActionAuditTransactions.STALE_REQUEST_AFTER));

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");
        assertThat(jdbc.queryForObject(
                "SELECT container_id_prefix FROM container_action_audit WHERE idempotency_key = ?",
                String.class, "v9-requested")).isEqualTo("not-a-public-id");
        assertThat(later.reconcileStaleRequested()).isOne();
        assertThat(later.find(current.record().operationId()).orElseThrow().status())
                .isEqualTo(ContainerActionStatus.OUTCOME_UNKNOWN);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM container_action_audit
                WHERE idempotency_key = 'v9-requested'
                  AND result = 'REQUESTED'
                """, Integer.class)).isOne();
    }

    @Test
    void should_backfillVisibilityXidWithoutChangingAuditShape_when_migratingFromV10ToCurrent() {
        database.migrateTo("10");
        insertCurrent("REQUESTED", null, null, "0123456789ab");
        insertCurrent("APPLIED", "APPLIED", NOW.plusSeconds(1), "abcdef012345");
        List<Map<String, Object>> before = auditShape();

        Flyway flyway = database.migrateToCurrent();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");
        assertThat(auditShape()).isEqualTo(before);
        assertThat(jdbc.queryForList("""
                SELECT recorded_xid::text
                FROM container_action_audit
                ORDER BY container_id_prefix
                """, String.class))
                .hasSize(2)
                .allSatisfy(xid -> assertThat(xid).isNotBlank());
    }

    @Test
    void should_enforceCurrentResultIdentifierAndTerminalShapeForNewWrites() {
        database.migrateToCurrent();
        insertCurrent("REQUESTED", null, null, "0123456789ab");
        insertCurrent("APPLIED", "APPLIED", NOW, "abcdef012345");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname = 'ix_container_action_result_requested'
                """, Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> insertCurrent(
                "REQUESTED", null, null, "invalid-id"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCurrent(
                "SUCCESS", "APPLIED", NOW, "111111111111"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCurrent(
                "FAILED", null, NOW, "222222222222"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCurrent(
                "FAILED", "not-stable", NOW, "333333333333"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertLegacy(String result, String key, String containerId) {
        jdbc.update("""
                INSERT INTO container_action_audit (
                    id, idempotency_key, requested_at, completed_at, principal,
                    action, container_id_prefix, container_name, image, result,
                    failure_summary, metadata)
                VALUES (?, ?, ?, ?, 'admin@example.test', 'RESTART', ?, 'legacy',
                        NULL, ?, NULL, '{}'::jsonb)
                """,
                UUID.randomUUID(), key, Timestamp.from(NOW),
                result.equals("REQUESTED") ? null : Timestamp.from(NOW.plusSeconds(1)),
                containerId, result);
    }

    private void insertCurrent(
            String result,
            String reasonCode,
            Instant completedAt,
            String containerId) {
        jdbc.update("""
                INSERT INTO container_action_audit (
                    id, idempotency_key, requested_at, completed_at, principal,
                    action, container_id_prefix, container_name, image, result,
                    reason_code, failure_summary, metadata)
                VALUES (?, ?, ?, ?, 'admin@example.test', 'START', ?, NULL,
                        NULL, ?, ?, NULL, '{}'::jsonb)
                """,
                UUID.randomUUID(), UUID.randomUUID().toString(), Timestamp.from(NOW),
                completedAt == null ? null : Timestamp.from(completedAt),
                containerId, result, reasonCode);
    }

    private ContainerActionAuditTransactions transactionsAt(Instant instant) {
        return new ContainerActionAuditTransactions(
                new ContainerActionAuditStore(jdbc),
                new DataSourceTransactionManager(database.dataSource()),
                Clock.fixed(instant, ZoneOffset.UTC),
                UUID::randomUUID);
    }

    private Map<String, String> statusesByKey() {
        return jdbc.query("""
                SELECT idempotency_key, result FROM container_action_audit
                """, result -> {
            java.util.LinkedHashMap<String, String> statuses = new java.util.LinkedHashMap<>();
            while (result.next()) {
                statuses.put(result.getString(1), result.getString(2));
            }
            return statuses;
        });
    }

    private List<Map<String, Object>> auditShape() {
        return jdbc.queryForList("""
                SELECT id, idempotency_key, requested_at, completed_at, principal,
                       action, container_id_prefix, container_name, image, result,
                       reason_code, failure_summary, metadata
                FROM container_action_audit
                ORDER BY container_id_prefix
                """);
    }
}
