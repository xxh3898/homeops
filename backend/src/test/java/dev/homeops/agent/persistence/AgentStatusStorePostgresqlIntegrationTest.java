package dev.homeops.agent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class AgentStatusStorePostgresqlIntegrationTest {
    private static final String AGENT_ID = "local-mac";
    private static final Instant NOW = Instant.parse("2026-08-07T04:30:00Z");

    private static JdbcTemplate jdbc;
    private static AgentStatusStore store;

    @BeforeAll
    static void migrateFromV1ToCurrentVersion() {
        var dataSource = new DriverManagerDataSource(
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_URL"),
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_USERNAME"),
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_PASSWORD"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new AgentStatusStore(jdbc);
    }

    @BeforeEach
    void clearAgentStatus() {
        jdbc.update("DELETE FROM agent_status");
    }

    @Test
    void should_keepNewerCurrentStatus_when_olderAndNewerUpdatesRace() throws Exception {
        Instant initialCapturedAt = NOW.minusSeconds(3);
        Instant olderCapturedAt = NOW.minusSeconds(2);
        Instant newerCapturedAt = NOW.minusSeconds(1);
        assertThat(store.insertIfAbsent(
                AGENT_ID, UUID.randomUUID(), "v1", initialCapturedAt, NOW)).isTrue();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> attempts = List.of(
                    executor.submit(() -> updateWhenReleased("v2", olderCapturedAt, ready, start)),
                    executor.submit(() -> updateWhenReleased("v3", newerCapturedAt, ready, start)));

            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(attempts.stream().map(this::resultOf).toList()).contains(true);
        }

        StatusRow current = status();
        assertThat(current.version()).isEqualTo("v3");
        assertThat(current.capturedAt()).isEqualTo(newerCapturedAt);
    }

    @Test
    void should_rejectStrictlyOlderCurrentStatusUpdate_when_snapshotArrivesOutOfOrder() {
        Instant olderCapturedAt = NOW.minusSeconds(2);
        Instant newerCapturedAt = NOW.minusSeconds(1);
        UUID initialSnapshotId = UUID.randomUUID();
        UUID newerSnapshotId = UUID.randomUUID();
        UUID delayedSnapshotId = UUID.randomUUID();
        assertThat(store.insertIfAbsent(
                AGENT_ID, initialSnapshotId, "v1", olderCapturedAt, NOW.minusSeconds(1))).isTrue();
        assertThat(store.updateIfCapturedAtIsNotOlder(
                AGENT_ID, newerSnapshotId, "v2", newerCapturedAt, NOW)).isTrue();

        assertThat(store.updateIfCapturedAtIsNotOlder(
                AGENT_ID, delayedSnapshotId, "v3", olderCapturedAt, NOW.plusSeconds(1))).isFalse();

        assertThat(status()).isEqualTo(new StatusRow("v2", newerSnapshotId, newerCapturedAt, NOW));
    }

    @Test
    void should_preserveExistingEqualCapturedAtPolicy_when_snapshotTimestampTies() {
        Instant capturedAt = NOW.minusSeconds(1);
        UUID initialSnapshotId = UUID.randomUUID();
        UUID tiedSnapshotId = UUID.randomUUID();
        assertThat(store.insertIfAbsent(
                AGENT_ID, initialSnapshotId, "v1", capturedAt, NOW)).isTrue();

        assertThat(store.updateIfCapturedAtIsNotOlder(
                AGENT_ID, tiedSnapshotId, "v2", capturedAt, NOW)).isTrue();

        assertThat(status()).isEqualTo(new StatusRow("v2", tiedSnapshotId, capturedAt, NOW));
    }

    private boolean updateWhenReleased(
            String version, Instant capturedAt, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return store.updateIfCapturedAtIsNotOlder(
                AGENT_ID, UUID.randomUUID(), version, capturedAt, NOW);
    }

    private boolean resultOf(Future<Boolean> attempt) {
        try {
            return attempt.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent agent status update did not complete", exception);
        }
    }

    private StatusRow status() {
        return jdbc.queryForObject("""
                SELECT agent_version, last_snapshot_id, last_captured_at, last_seen_at
                FROM agent_status WHERE agent_id = ?
                """, (row, index) -> new StatusRow(
                row.getString("agent_version"), row.getObject("last_snapshot_id", UUID.class),
                row.getTimestamp("last_captured_at").toInstant(),
                row.getTimestamp("last_seen_at").toInstant()), AGENT_ID);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured for this test");
        return value;
    }

    private record StatusRow(String version, UUID snapshotId, Instant capturedAt, Instant seenAt) { }
}
