package dev.homeops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homeops.monitoring.HttpServiceChecker.Result;
import dev.homeops.monitoring.api.MonitoredServiceResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class PostgresqlMonitoringIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    private static JdbcTemplate jdbc;
    private static MonitoredServiceStore store;
    private static Flyway flyway;

    @BeforeAll
    static void migrateFromV1ToCurrentVersion() {
        var dataSource = new DriverManagerDataSource(
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_URL"),
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_USERNAME"),
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_PASSWORD"));
        flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new MonitoredServiceStore(jdbc);
    }

    @BeforeEach
    void clearMonitoringTables() {
        jdbc.update("DELETE FROM incident");
        jdbc.update("DELETE FROM health_check_result");
        jdbc.update("DELETE FROM monitored_service");
    }

    @Test
    void should_applyFlywayMigrationsFromV1ToV10WithV4ActiveIncidentIndex() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("10");
        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?",
                Integer.class, "uk_incident_service_open")).isEqualTo(1);
    }

    @Test
    void should_allowOnlyOneOpenIncident_when_twoChecksOpenConcurrently() throws Exception {
        MonitoredServiceResponse service = createService();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> attempts = List.of(
                    executor.submit(() -> openWhenReleased(service, ready, start)),
                    executor.submit(() -> openWhenReleased(service, ready, start)));

            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(attempts.stream().map(this::resultOf).toList())
                    .containsExactlyInAnyOrder(true, false);
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM incident WHERE service_id = ? AND status IN ('OPEN', 'ACKNOWLEDGED')",
                Integer.class, service.id())).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("statuses")
    void should_deleteExpiredCompletedStreak_when_laterOppositeStatusExists(String status) {
        MonitoredServiceResponse service = createService();
        record(service.id(), NOW.minus(Duration.ofDays(2)), status);
        record(service.id(), NOW.minus(Duration.ofDays(1)), oppositeStatus(status));

        int deleted = store.deleteExpiredResults(
                NOW.minus(Duration.ofHours(12)), NOW.minus(Duration.ofHours(12)));

        assertThat(deleted).isEqualTo(1);
        assertThat(resultCount(service.id(), status)).isZero();
    }

    @ParameterizedTest
    @MethodSource("statuses")
    void should_preserveOneActiveResult_when_thresholdIsOne(String status) {
        MonitoredServiceResponse service = createService();
        record(service.id(), NOW.minus(Duration.ofDays(2)), status);

        int deleted = store.deleteExpiredResults(
                NOW.minus(Duration.ofHours(12)), NOW.minus(Duration.ofHours(12)));

        assertThat(deleted).isZero();
        assertThat(resultCount(service.id(), status)).isEqualTo(1);
        assertThat(store.consecutiveStatusCount(service.id(), status)).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("statuses")
    void should_preserveExactlyOneHundredActiveResults_when_thresholdIsHundred(String status) {
        MonitoredServiceResponse service = createService();
        for (int index = 0; index < 100; index++) {
            record(service.id(), NOW.minus(Duration.ofDays(2)).plusSeconds(index), status);
        }

        int before = store.consecutiveStatusCount(service.id(), status);
        int deleted = store.deleteExpiredResults(
                NOW.minus(Duration.ofHours(12)), NOW.minus(Duration.ofHours(12)));

        assertThat(deleted).isZero();
        assertThat(resultCount(service.id(), status)).isEqualTo(100);
        assertThat(before).isEqualTo(100);
        assertThat(store.consecutiveStatusCount(service.id(), status)).isEqualTo(100);
    }

    @ParameterizedTest
    @MethodSource("statuses")
    void should_keepLatestHundredActiveResults_when_activeStreakHasOneHundredOneRows(String status) {
        MonitoredServiceResponse service = createService();
        for (int index = 0; index < 101; index++) {
            record(service.id(), NOW.minus(Duration.ofDays(2)).plusSeconds(index), status);
        }

        int before = store.consecutiveStatusCount(service.id(), status);
        int deleted = store.deleteExpiredResults(
                NOW.minus(Duration.ofHours(12)), NOW.minus(Duration.ofHours(12)));

        assertThat(deleted).isEqualTo(1);
        assertThat(resultCount(service.id(), status)).isEqualTo(100);
        assertThat(before).isEqualTo(100);
        assertThat(store.consecutiveStatusCount(service.id(), status)).isEqualTo(100);
    }

    @Test
    void should_preserveExpiredHealthyBoundary_when_currentDownStreakFollowsOlderDownStreak() {
        MonitoredServiceResponse service = createService();
        record(service.id(), NOW.minus(Duration.ofDays(40)).minusSeconds(1), "DOWN");
        record(service.id(), NOW.minus(Duration.ofDays(40)), "DOWN");
        record(service.id(), NOW.minus(Duration.ofDays(8)).minusSeconds(1), "HEALTHY");
        record(service.id(), NOW.minus(Duration.ofDays(8)), "HEALTHY");
        record(service.id(), NOW.minus(Duration.ofMinutes(2)), "DOWN");
        record(service.id(), NOW.minus(Duration.ofMinutes(1)), "DOWN");
        record(service.id(), NOW, "DOWN");

        int before = store.consecutiveStatusCount(service.id(), "DOWN");
        int deleted = store.deleteExpiredResults(
                NOW.minus(Duration.ofDays(7)), NOW.minus(Duration.ofDays(30)));

        assertThat(deleted).isEqualTo(3);
        assertThat(resultCount(service.id(), "HEALTHY")).isEqualTo(1);
        assertThat(resultCount(service.id(), "DOWN")).isEqualTo(3);
        assertThat(before).isEqualTo(3);
        assertThat(store.consecutiveStatusCount(service.id(), "DOWN")).isEqualTo(3);
    }

    @Test
    void should_preserveExpiredDownBoundary_when_currentHealthyStreakFollowsOlderHealthyStreak() {
        MonitoredServiceResponse service = createService();
        record(service.id(), NOW.minus(Duration.ofDays(40)).minusSeconds(1), "HEALTHY");
        record(service.id(), NOW.minus(Duration.ofDays(40)), "HEALTHY");
        record(service.id(), NOW.minus(Duration.ofDays(31)).minusSeconds(1), "DOWN");
        record(service.id(), NOW.minus(Duration.ofDays(31)), "DOWN");
        record(service.id(), NOW.minus(Duration.ofMinutes(2)), "HEALTHY");
        record(service.id(), NOW.minus(Duration.ofMinutes(1)), "HEALTHY");
        record(service.id(), NOW, "HEALTHY");

        int before = store.consecutiveStatusCount(service.id(), "HEALTHY");
        int deleted = store.deleteExpiredResults(
                NOW.minus(Duration.ofDays(7)), NOW.minus(Duration.ofDays(30)));

        assertThat(deleted).isEqualTo(3);
        assertThat(resultCount(service.id(), "DOWN")).isEqualTo(1);
        assertThat(resultCount(service.id(), "HEALTHY")).isEqualTo(3);
        assertThat(before).isEqualTo(3);
        assertThat(store.consecutiveStatusCount(service.id(), "HEALTHY")).isEqualTo(3);
    }

    private boolean openWhenReleased(
            MonitoredServiceResponse service, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return store.openIncident(service, NOW).isPresent();
    }

    private boolean resultOf(Future<Boolean> attempt) {
        try {
            return attempt.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent incident attempt did not complete", exception);
        }
    }

    private MonitoredServiceResponse createService() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO monitored_service
                    (id, name, url, http_method, expected_status, timeout_ms, interval_seconds,
                     failure_threshold, recovery_threshold, severity, enabled, notification_enabled)
                VALUES (?, ?, ?, 'GET', 200, 3000, 5, 100, 100, 'WARNING', TRUE, TRUE)
                """, id, "service-" + id, "https://service.example.invalid/health");
        return new MonitoredServiceResponse(id, "service-" + id,
                "https://service.example.invalid/health", "GET", 200, 3_000, 5,
                100, 100, "WARNING", true, true);
    }

    private void record(UUID serviceId, Instant checkedAt, String status) {
        boolean healthy = status.equals("HEALTHY");
        store.recordResult(serviceId, checkedAt,
                new Result(healthy, healthy ? 200 : null, 20, healthy ? null : "timeout"));
    }

    private int resultCount(UUID serviceId, String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM health_check_result WHERE service_id = ? AND status = ?",
                Integer.class, serviceId, status);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured for this test");
        return value;
    }

    private static Stream<String> statuses() {
        return Stream.of("HEALTHY", "DOWN");
    }

    private static String oppositeStatus(String status) {
        return status.equals("HEALTHY") ? "DOWN" : "HEALTHY";
    }
}
