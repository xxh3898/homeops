package dev.homeops.metrics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import dev.homeops.agent.persistence.HostMetricAggregateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HostMetricRetentionJobTest {

    @Mock
    private HostMetricAggregateRepository repository;

    @Test
    void should_deleteOnlyExpiredAggregates_when_scheduledCleanupRuns() {
        Instant now = Instant.parse("2026-08-04T12:00:00Z");
        var properties = new HomeOpsMetricProperties(Duration.ofDays(30));
        var job = new HostMetricRetentionJob(
                repository,
                properties,
                Clock.fixed(now, ZoneOffset.UTC));

        job.deleteExpiredAggregates();

        verify(repository).deleteByBucketStartBefore(
                Instant.parse("2026-07-05T12:00:00Z"));
    }

    @Test
    void should_rejectConfiguration_when_retentionIsNotPositive() {
        assertThatThrownBy(() -> new HomeOpsMetricProperties(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metric retention must be between one second and 365 days");
    }
}
