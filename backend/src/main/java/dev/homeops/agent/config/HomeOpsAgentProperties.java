package dev.homeops.agent.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("homeops.agent")
public record HomeOpsAgentProperties(
        @NotBlank String expectedId,
        @NotNull Duration staleAfter,
        @NotNull Duration maximumSnapshotAge,
        @NotNull Duration allowedFutureSkew,
        @Positive int maximumContainers,
        @NotNull Duration processedSnapshotRetention) {

    private static final Duration MAXIMUM_STALE_AFTER = Duration.ofDays(30);
    private static final Duration MAXIMUM_ALLOWED_FUTURE_SKEW = Duration.ofMinutes(15);

    public HomeOpsAgentProperties {
        if (staleAfter != null && (staleAfter.isNegative() || staleAfter.isZero()
                || staleAfter.compareTo(MAXIMUM_STALE_AFTER) > 0)) {
            throw new IllegalArgumentException("Agent stale threshold must be positive and at most 30 days");
        }
        if (maximumSnapshotAge != null && (maximumSnapshotAge.isNegative() || maximumSnapshotAge.isZero())) {
            throw new IllegalArgumentException("Agent maximum snapshot age must be positive");
        }
        if (allowedFutureSkew != null && (allowedFutureSkew.isNegative()
                || allowedFutureSkew.compareTo(MAXIMUM_ALLOWED_FUTURE_SKEW) > 0)) {
            throw new IllegalArgumentException("Agent allowed future skew must be between zero and 15 minutes");
        }
        if (processedSnapshotRetention != null
                && maximumSnapshotAge != null
                && (processedSnapshotRetention.compareTo(maximumSnapshotAge) <= 0
                || processedSnapshotRetention.compareTo(Duration.ofDays(30)) > 0)) {
            throw new IllegalArgumentException(
                    "Processed snapshot retention must be longer than the maximum snapshot age and at most 30 days");
        }
    }
}
