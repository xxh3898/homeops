package dev.homeops.ingestion.api;

import dev.homeops.common.PostgresqlTimestamp;
import dev.homeops.common.validation.NoPostgresqlNul;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record DeploymentIngestionRequest(
        @NotBlank @Size(max = 128) @NoPostgresqlNul String eventKey,
        @NotBlank @Size(max = 128) @NoPostgresqlNul String project,
        @NotBlank @Size(max = 32) @NoPostgresqlNul String environment,
        @Size(max = 128) @NoPostgresqlNul String branch,
        @NotBlank @Pattern(regexp = "[0-9a-f]{40}") @NoPostgresqlNul String commitSha,
        @Size(max = 256) @NoPostgresqlNul String imageTag,
        @Pattern(regexp = "[0-9a-f]{40}") @NoPostgresqlNul String previousCommitSha,
        @NotNull DeploymentStatus status,
        @NotNull Instant startedAt,
        Instant finishedAt,
        @Size(max = 128) @NoPostgresqlNul String failureStage,
        @Size(max = 1024) @NoPostgresqlNul String failureSummary,
        @Size(max = 128) @NoPostgresqlNul String actor,
        @Size(max = 64) @NoPostgresqlNul String workflowRunId,
        @Size(max = 2048) @NoPostgresqlNul String workflowRunUrl,
        boolean rollback) {

    @AssertTrue(message = "Deployment timestamps must be within PostgreSQL's supported range")
    public boolean isPostgresqlTimestampRangeSupported() {
        return isSupportedIfPresent(startedAt) && isSupportedIfPresent(finishedAt);
    }

    private static boolean isSupportedIfPresent(Instant timestamp) {
        return timestamp == null || PostgresqlTimestamp.isSupported(timestamp);
    }

    public enum DeploymentStatus {
        REQUESTED, RUNNING, SUCCESS, FAILED, ROLLED_BACK, CANCELLED
    }
}
