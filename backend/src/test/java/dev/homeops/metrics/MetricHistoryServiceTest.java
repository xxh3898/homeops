package dev.homeops.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.metrics.HostMetricHistoryStore.HostMetricHistoryRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MetricHistoryServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-17T12:34:45Z");

    @Mock private HostMetricHistoryStore store;

    @ParameterizedTest
    @CsvSource({
            "1h,2026-08-17T11:34:00Z,2026-08-17T12:34:00Z,60,60",
            "6h,2026-08-17T06:30:00Z,2026-08-17T12:30:00Z,300,72",
            "24h,2026-08-16T12:30:00Z,2026-08-17T12:30:00Z,900,96",
            "7d,2026-08-10T12:00:00Z,2026-08-17T12:00:00Z,3600,168"
    })
    void should_queryAlignedCompletedRange_when_periodIsSupported(
            String period,
            Instant expectedFrom,
            Instant expectedTo,
            long bucketSeconds,
            int maxPoints) {
        when(store.find("local-mac", expectedFrom, expectedTo, bucketSeconds, maxPoints))
                .thenReturn(List.of());
        MetricHistoryService service = service();

        var response = service.history(period);

        assertThat(response.period()).isEqualTo(period);
        assertThat(response.from()).isEqualTo(expectedFrom);
        assertThat(response.to()).isEqualTo(expectedTo);
        assertThat(response.bucketSeconds()).isEqualTo(bucketSeconds);
        assertThat(response.points()).isEmpty();
        verify(store).find("local-mac", expectedFrom, expectedTo, bucketSeconds, maxPoints);
    }

    @Test
    void should_mapPersistedMetricSemantics_when_storeReturnsSparseRows() {
        Instant from = Instant.parse("2026-08-17T11:34:00Z");
        Instant to = Instant.parse("2026-08-17T12:34:00Z");
        HostMetricHistoryRow row = new HostMetricHistoryRow(
                Instant.parse("2026-08-17T12:00:00Z"),
                12,
                12.5,
                35.0,
                16_000,
                8_000,
                9_000,
                1_000_000,
                250_000);
        when(store.find("local-mac", from, to, 60, 60)).thenReturn(List.of(row));

        var response = service().history("1h");

        assertThat(response.points()).singleElement().satisfies(point -> {
            assertThat(point.bucketStart()).isEqualTo(row.bucketStart());
            assertThat(point.sampleCount()).isEqualTo(12);
            assertThat(point.cpuUsageAverage()).isEqualTo(12.5);
            assertThat(point.cpuUsagePeak()).isEqualTo(35.0);
            assertThat(point.memoryTotalBytes()).isEqualTo(16_000);
            assertThat(point.memoryUsedAverageBytes()).isEqualTo(8_000);
            assertThat(point.memoryUsedPeakBytes()).isEqualTo(9_000);
            assertThat(point.diskTotalBytes()).isEqualTo(1_000_000);
            assertThat(point.diskUsedBytes()).isEqualTo(250_000);
        });
    }

    private MetricHistoryService service() {
        return new MetricHistoryService(agentProperties(), store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HomeOpsAgentProperties agentProperties() {
        return new HomeOpsAgentProperties(
                "local-mac",
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                Duration.ofMinutes(1),
                128,
                Duration.ofDays(1));
    }
}
