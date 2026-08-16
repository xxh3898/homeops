package dev.homeops.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

public enum MetricHistoryPeriod {
    ONE_HOUR("1h", Duration.ofHours(1), Duration.ofMinutes(1), 60),
    SIX_HOURS("6h", Duration.ofHours(6), Duration.ofMinutes(5), 72),
    TWENTY_FOUR_HOURS("24h", Duration.ofHours(24), Duration.ofMinutes(15), 96),
    SEVEN_DAYS("7d", Duration.ofDays(7), Duration.ofHours(1), 168);

    private final String wireValue;
    private final Duration duration;
    private final Duration bucket;
    private final int maxPoints;

    MetricHistoryPeriod(
            String wireValue,
            Duration duration,
            Duration bucket,
            int maxPoints) {
        this.wireValue = wireValue;
        this.duration = duration;
        this.bucket = bucket;
        this.maxPoints = maxPoints;
        if (duration.toSeconds() / bucket.toSeconds() != maxPoints
                || duration.toSeconds() % bucket.toSeconds() != 0) {
            throw new IllegalArgumentException("Metric history period must have an exact point bound");
        }
    }

    public String wireValue() {
        return wireValue;
    }

    public Duration duration() {
        return duration;
    }

    public long bucketSeconds() {
        return bucket.toSeconds();
    }

    public int maxPoints() {
        return maxPoints;
    }

    public Instant alignEnd(Instant instant) {
        long bucketSeconds = bucketSeconds();
        long alignedEpochSecond = Math.floorDiv(instant.getEpochSecond(), bucketSeconds)
                * bucketSeconds;
        return Instant.ofEpochSecond(alignedEpochSecond);
    }

    public static MetricHistoryPeriod parse(String value) {
        return Arrays.stream(values())
                .filter(period -> period.wireValue.equals(value))
                .findFirst()
                .orElseThrow(InvalidMetricHistoryPeriodException::new);
    }
}
