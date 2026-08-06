package dev.homeops.monitoring.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("homeops.monitoring")
public record HomeOpsMonitoringProperties(
        List<String> allowedOrigins,
        @NotNull Duration healthyResultRetention,
        @NotNull Duration failureResultRetention,
        @Min(1) @Max(16) int maxConcurrentChecks) {

    public HomeOpsMonitoringProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (healthyResultRetention == null || healthyResultRetention.isNegative()
                || healthyResultRetention.isZero()) {
            throw new IllegalArgumentException("Healthy result retention must be positive");
        }
        if (failureResultRetention == null || failureResultRetention.isNegative()
                || failureResultRetention.isZero()) {
            throw new IllegalArgumentException("Failure result retention must be positive");
        }
        if (healthyResultRetention.compareTo(Duration.ofDays(365)) > 0
                || failureResultRetention.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("Check result retention must not exceed 365 days");
        }
        if (maxConcurrentChecks < 1 || maxConcurrentChecks > 16) {
            throw new IllegalArgumentException("Maximum concurrent checks must be between 1 and 16");
        }
    }
}
