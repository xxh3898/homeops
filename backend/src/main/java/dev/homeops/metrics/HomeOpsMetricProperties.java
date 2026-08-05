package dev.homeops.metrics;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("homeops.metrics")
public record HomeOpsMetricProperties(@NotNull Duration retention) {

    public HomeOpsMetricProperties {
        if (retention != null
                && (retention.isZero()
                || retention.isNegative()
                || retention.compareTo(Duration.ofDays(365)) > 0)) {
            throw new IllegalArgumentException(
                    "Metric retention must be between one second and 365 days");
        }
    }
}
