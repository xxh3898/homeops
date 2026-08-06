package dev.homeops.monitoring;

import static org.mockito.Mockito.verify;

import dev.homeops.monitoring.config.HomeOpsMonitoringProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HealthCheckRetentionJobTest {
    @Mock private MonitoredServiceStore store;

    @Test
    void should_applySeparateRetention_when_cleanupRuns() {
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        HomeOpsMonitoringProperties properties = new HomeOpsMonitoringProperties(
                List.of(), Duration.ofDays(7), Duration.ofDays(30), 4);
        HealthCheckRetentionJob job = new HealthCheckRetentionJob(
                store, properties, Clock.fixed(now, ZoneOffset.UTC));

        job.removeExpiredResults();

        verify(store).deleteResultsOlderThan("HEALTHY", now.minus(Duration.ofDays(7)));
        verify(store).deleteResultsOlderThan("DOWN", now.minus(Duration.ofDays(30)));
    }
}
