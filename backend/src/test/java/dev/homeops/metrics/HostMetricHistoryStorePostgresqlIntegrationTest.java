package dev.homeops.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class HostMetricHistoryStorePostgresqlIntegrationTest {
    private static final String EXPECTED_AGENT = "local-mac";

    private static JdbcTemplate jdbc;
    private static HostMetricHistoryStore store;

    @BeforeAll
    static void migrateAndCreateStore() {
        var dataSource = new DriverManagerDataSource(
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_URL"),
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_USERNAME"),
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_PASSWORD"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new HostMetricHistoryStore(jdbc);
    }

    @BeforeEach
    void clearMetricHistory() {
        jdbc.update("DELETE FROM host_metric_aggregate");
    }

    @Test
    void should_weightAveragesSumSamplesAndUseLatestValues_when_minutesShareOutputBucket() {
        Instant from = Instant.parse("2026-08-17T12:00:00Z");
        Instant to = Instant.parse("2026-08-17T12:05:00Z");
        insert(EXPECTED_AGENT, from.minusSeconds(60), 5, 90, 95,
                9_000, 8_000, 8_500, 9_000, 8_000);
        insert(EXPECTED_AGENT, from, 2, 10, 30,
                1_000, 200, 300, 1_000, 900);
        insert(EXPECTED_AGENT, from.plusSeconds(60), 1, 40, 50,
                2_000, 500, 600, 2_000, 400);
        insert("other-agent", from.plusSeconds(120), 20, 99, 100,
                4_000, 3_000, 3_500, 4_000, 3_000);
        insert(EXPECTED_AGENT, to, 5, 80, 90,
                3_000, 2_000, 2_500, 3_000, 2_000);

        var rows = store.find(EXPECTED_AGENT, from, to, 300, 72);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.bucketStart()).isEqualTo(from);
            assertThat(row.sampleCount()).isEqualTo(3);
            assertThat(row.cpuUsageAverage()).isEqualTo(20.0);
            assertThat(row.cpuUsagePeak()).isEqualTo(50.0);
            assertThat(row.memoryTotalBytes()).isEqualTo(2_000);
            assertThat(row.memoryUsedAverageBytes()).isEqualTo(300);
            assertThat(row.memoryUsedPeakBytes()).isEqualTo(600);
            assertThat(row.diskTotalBytes()).isEqualTo(2_000);
            assertThat(row.diskUsedBytes()).isEqualTo(400);
        });
    }

    @Test
    void should_omitMissingBucketsAndOrderResults_when_collectionHasGap() {
        Instant from = Instant.parse("2026-08-17T12:00:00Z");
        Instant to = Instant.parse("2026-08-17T12:15:00Z");
        insert(EXPECTED_AGENT, from.plusSeconds(10 * 60), 1, 30, 40,
                1_000, 300, 350, 1_000, 300);
        insert(EXPECTED_AGENT, from, 1, 10, 20,
                1_000, 100, 150, 1_000, 100);

        var rows = store.find(EXPECTED_AGENT, from, to, 300, 72);

        assertThat(rows)
                .extracting(HostMetricHistoryStore.HostMetricHistoryRow::bucketStart)
                .containsExactly(from, from.plusSeconds(10 * 60));
    }

    @Test
    void should_returnAtMostOnePointPerMinute_when_oneHourRangeIsFull() {
        Instant from = Instant.parse("2026-08-17T11:00:00Z");
        Instant to = Instant.parse("2026-08-17T12:00:00Z");
        for (int minute = 0; minute < 60; minute++) {
            insert(EXPECTED_AGENT, from.plusSeconds(minute * 60L), 1, minute, minute,
                    1_000, 500, 500, 1_000, 500);
        }

        var rows = store.find(EXPECTED_AGENT, from, to, 60, 60);

        assertThat(rows).hasSize(60);
        assertThat(rows.getFirst().bucketStart()).isEqualTo(from);
        assertThat(rows.getLast().bucketStart()).isEqualTo(to.minusSeconds(60));
    }

    private static void insert(
            String agentId,
            Instant bucketStart,
            int sampleCount,
            double cpuAverage,
            double cpuPeak,
            long memoryTotal,
            long memoryAverage,
            long memoryPeak,
            long diskTotal,
            long diskUsed) {
        jdbc.update("""
                INSERT INTO host_metric_aggregate
                    (id, agent_id, bucket_start, sample_count, cpu_usage_average, cpu_usage_peak,
                     memory_total_bytes, memory_used_average_bytes, memory_used_peak_bytes,
                     disk_total_bytes, disk_used_bytes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), agentId, Timestamp.from(bucketStart), sampleCount,
                cpuAverage, cpuPeak, memoryTotal, memoryAverage, memoryPeak, diskTotal, diskUsed);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured for this test");
        }
        return value;
    }
}
