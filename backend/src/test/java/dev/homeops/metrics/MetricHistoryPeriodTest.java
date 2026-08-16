package dev.homeops.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MetricHistoryPeriodTest {

    @ParameterizedTest
    @CsvSource({
            "1h,2026-08-17T12:34:00Z,60,60",
            "6h,2026-08-17T12:30:00Z,300,72",
            "24h,2026-08-17T12:30:00Z,900,96",
            "7d,2026-08-17T12:00:00Z,3600,168"
    })
    void should_alignEndAndPreservePointBound_when_periodIsSupported(
            String wireValue,
            Instant expectedEnd,
            long expectedBucketSeconds,
            int expectedMaxPoints) {
        MetricHistoryPeriod period = MetricHistoryPeriod.parse(wireValue);

        assertThat(period.alignEnd(Instant.parse("2026-08-17T12:34:45.999Z")))
                .isEqualTo(expectedEnd);
        assertThat(period.bucketSeconds()).isEqualTo(expectedBucketSeconds);
        assertThat(period.maxPoints()).isEqualTo(expectedMaxPoints);
        assertThat(period.duration().toSeconds() / period.bucketSeconds())
                .isEqualTo(expectedMaxPoints);
    }

    @Test
    void should_rejectPeriod_when_valueIsMissingOrUnsupported() {
        assertThatThrownBy(() -> MetricHistoryPeriod.parse(null))
                .isInstanceOf(InvalidMetricHistoryPeriodException.class);
        assertThatThrownBy(() -> MetricHistoryPeriod.parse("30d"))
                .isInstanceOf(InvalidMetricHistoryPeriodException.class);
    }
}
