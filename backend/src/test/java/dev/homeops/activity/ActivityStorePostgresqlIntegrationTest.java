package dev.homeops.activity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homeops.activity.api.ActivityEventResponse;
import dev.homeops.activity.api.ActivityEventResponse.Severity;
import dev.homeops.activity.api.ActivityEventResponse.Type;
import dev.homeops.activity.api.ActivityPageResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class ActivityStorePostgresqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-23T01:00:00Z");
    private static final String CONTAINER_ID = "0123456789ab";

    private PostgresqlActivityTestDatabase database;
    private JdbcTemplate jdbc;
    private ActivityService service;

    @BeforeEach
    void createSchema() {
        database = PostgresqlActivityTestDatabase.create();
        database.migrateToCurrent();
        jdbc = database.jdbc();
        service = new ActivityService(new ActivityStore(jdbc), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void dropSchema() {
        database.close();
    }

    @Test
    void should_projectBoundedContainerActionsWithDeterministicSeverity() {
        insertAction(uuid(101), "START", "REQUESTED", NOW);
        insertAction(uuid(102), "START", "APPLIED", NOW.minusSeconds(1));
        insertAction(uuid(103), "STOP", "NOOP", NOW.minusSeconds(2));
        insertAction(uuid(104), "STOP", "DENIED", NOW.minusSeconds(3));
        insertAction(uuid(105), "RESTART", "FAILED", NOW.minusSeconds(4));
        insertAction(uuid(106), "RESTART", "OUTCOME_UNKNOWN", NOW.minusSeconds(5));
        insertAction(uuid(107), "START", "EXPIRED", NOW.minusSeconds(6));

        Map<String, ActivityEventResponse> byStatus = new LinkedHashMap<>();
        service.page(null, 100).items().forEach(event -> byStatus.put(event.status(), event));

        assertThat(byStatus).hasSize(7);
        assertThat(byStatus.values()).allSatisfy(event -> {
            assertThat(event.type()).isEqualTo(Type.CONTAINER_ACTION);
            assertThat(event.context()).isEqualTo(CONTAINER_ID);
        });
        assertThat(byStatus.get("REQUESTED").title()).isEqualTo("Container start");
        assertThat(byStatus.get("APPLIED").title()).isEqualTo("Container start");
        assertThat(byStatus.get("NOOP").title()).isEqualTo("Container stop");
        assertThat(byStatus.get("DENIED").title()).isEqualTo("Container stop");
        assertThat(byStatus.get("FAILED").title()).isEqualTo("Container restart");
        assertThat(byStatus.get("OUTCOME_UNKNOWN").title()).isEqualTo("Container restart");
        assertThat(byStatus.get("EXPIRED").title()).isEqualTo("Container start");
        assertThat(byStatus.get("APPLIED").severity()).isEqualTo(Severity.INFO);
        assertThat(byStatus.get("NOOP").severity()).isEqualTo(Severity.INFO);
        assertThat(byStatus.get("REQUESTED").severity()).isEqualTo(Severity.WARNING);
        assertThat(byStatus.get("DENIED").severity()).isEqualTo(Severity.WARNING);
        assertThat(byStatus.get("EXPIRED").severity()).isEqualTo(Severity.WARNING);
        assertThat(byStatus.get("FAILED").severity()).isEqualTo(Severity.CRITICAL);
        assertThat(byStatus.get("OUTCOME_UNKNOWN").severity()).isEqualTo(Severity.CRITICAL);
        assertThat(byStatus.get("REQUESTED").occurredAt()).isEqualTo(NOW);
    }

    @Test
    void should_excludeContainerActionInsertedAfterFirstPageVisibilitySnapshot() {
        insertAction(uuid(201), "START", "APPLIED", NOW);
        insertAction(uuid(202), "STOP", "APPLIED", NOW.minusSeconds(1));

        ActivityPageResponse first = service.page(null, 1);
        insertAction(uuid(203), "RESTART", "APPLIED", NOW.plusSeconds(60));
        ActivityPageResponse second = service.page(first.nextCursor(), 1);

        assertThat(first.items()).extracting(ActivityEventResponse::id)
                .containsExactly(uuid(201).toString());
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(second.items()).extracting(ActivityEventResponse::id)
                .containsExactly(uuid(202).toString());
        assertThat(second.items()).extracting(ActivityEventResponse::id)
                .doesNotContain(uuid(203).toString());
    }

    @Test
    void should_pageMixedSourcesAtSameTimestampWithoutDuplicatesOrSkips() {
        insertDeployment(uuid(301));
        insertBackup(uuid(302));
        insertIncident(uuid(303));
        insertAgent(uuid(304));
        insertAction(uuid(305), "RESTART", "APPLIED", NOW);

        List<ActivityEventResponse> events = new ArrayList<>();
        String cursor = null;
        do {
            ActivityPageResponse page = service.page(cursor, 2);
            events.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(events).extracting(event -> event.type() + ":" + event.id())
                .containsExactlyInAnyOrder(
                        "DEPLOYMENT:" + uuid(301),
                        "BACKUP:" + uuid(302),
                        "INCIDENT:" + uuid(303),
                        "AGENT:" + uuid(304),
                        "CONTAINER_ACTION:" + uuid(305));
        assertThat(events).extracting(event -> event.type() + ":" + event.id())
                .doesNotHaveDuplicates();
    }

    private void insertAction(UUID id, String action, String status, Instant requestedAt) {
        boolean terminal = !status.equals("REQUESTED");
        jdbc.update("""
                INSERT INTO container_action_audit (
                    id, idempotency_key, requested_at, completed_at, principal,
                    action, container_id_prefix, container_name, image, result,
                    reason_code, failure_summary, metadata)
                VALUES (?, ?, ?, ?, 'admin@example.test', ?, ?, NULL, NULL, ?, ?, NULL, '{}'::jsonb)
                """,
                id,
                id.toString(),
                Timestamp.from(requestedAt),
                terminal ? Timestamp.from(requestedAt.plusSeconds(1)) : null,
                action,
                CONTAINER_ID,
                status,
                terminal ? "TEST_" + status : null);
    }

    private void insertDeployment(UUID id) {
        jdbc.update("""
                INSERT INTO deployment (
                    id, event_key, project, environment, commit_sha, status, started_at)
                VALUES (?, ?, 'example', 'production', ?, 'SUCCESS', ?)
                """, id, id.toString(), "a".repeat(40), Timestamp.from(NOW));
    }

    private void insertBackup(UUID id) {
        jdbc.update("""
                INSERT INTO backup_run (
                    id, event_key, project, database_type, status, started_at)
                VALUES (?, ?, 'example', 'POSTGRESQL', 'SUCCESS', ?)
                """, id, id.toString(), Timestamp.from(NOW));
    }

    private void insertIncident(UUID id) {
        jdbc.update("""
                INSERT INTO incident (
                    id, incident_type, severity, status, title, opened_at, last_observed_at)
                VALUES (?, 'SERVICE_UNAVAILABLE', 'CRITICAL', 'OPEN', 'Service unavailable', ?, ?)
                """, id, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void insertAgent(UUID id) {
        jdbc.update("""
                INSERT INTO agent_event (
                    id, agent_id, event_type, agent_version, occurred_at, summary)
                VALUES (?, 'agent-1', 'CONNECTED', 'test-version', ?, 'Agent connected')
                """, id, Timestamp.from(NOW));
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("10000000-0000-0000-0000-" + String.format("%012d", suffix));
    }

    private static final class PostgresqlActivityTestDatabase implements AutoCloseable {
        private final String schema;
        private final DriverManagerDataSource adminDataSource;
        private final DriverManagerDataSource schemaDataSource;

        private PostgresqlActivityTestDatabase() {
            schema = "activity_" + UUID.randomUUID().toString().replace("-", "");
            adminDataSource = new DriverManagerDataSource(
                    requiredEnvironment("HOMEOPS_TEST_POSTGRES_URL"),
                    requiredEnvironment("HOMEOPS_TEST_POSTGRES_USERNAME"),
                    requiredEnvironment("HOMEOPS_TEST_POSTGRES_PASSWORD"));
            new JdbcTemplate(adminDataSource).execute("CREATE SCHEMA " + schema);

            String baseUrl = requiredEnvironment("HOMEOPS_TEST_POSTGRES_URL");
            String separator = baseUrl.contains("?") ? "&" : "?";
            schemaDataSource = new DriverManagerDataSource(
                    baseUrl + separator + "currentSchema=" + schema,
                    requiredEnvironment("HOMEOPS_TEST_POSTGRES_USERNAME"),
                    requiredEnvironment("HOMEOPS_TEST_POSTGRES_PASSWORD"));
        }

        static PostgresqlActivityTestDatabase create() {
            return new PostgresqlActivityTestDatabase();
        }

        void migrateToCurrent() {
            Flyway.configure()
                    .dataSource(schemaDataSource)
                    .locations("classpath:db/migration")
                    .defaultSchema(schema)
                    .schemas(schema)
                    .load()
                    .migrate();
        }

        JdbcTemplate jdbc() {
            return new JdbcTemplate(schemaDataSource);
        }

        @Override
        public void close() {
            new JdbcTemplate(adminDataSource).execute("DROP SCHEMA " + schema + " CASCADE");
        }

        private static String requiredEnvironment(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + " must be configured for this test");
            }
            return value;
        }
    }
}
