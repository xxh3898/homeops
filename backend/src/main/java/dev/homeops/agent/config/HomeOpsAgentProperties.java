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
        @Positive int maximumContainers) {
}

