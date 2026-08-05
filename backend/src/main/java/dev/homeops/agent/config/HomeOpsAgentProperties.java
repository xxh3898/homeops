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

    public HomeOpsAgentProperties {
        if (processedSnapshotRetention != null
                && maximumSnapshotAge != null
                && (processedSnapshotRetention.compareTo(maximumSnapshotAge) <= 0
                || processedSnapshotRetention.compareTo(Duration.ofDays(30)) > 0)) {
            throw new IllegalArgumentException(
                    "Processed snapshot retention must be longer than the maximum snapshot age and at most 30 days");
        }
    }
}
