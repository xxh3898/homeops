package dev.homeops.ingestion.api;

import dev.homeops.common.PostgresqlTimestampRange;
import dev.homeops.common.validation.NoPostgresqlNul;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record BackupIngestionRequest(
        @NotBlank @Size(max = 128) @NoPostgresqlNul String eventKey,
        @NotBlank @Size(max = 128) @NoPostgresqlNul String project,
        @NotBlank @Size(max = 32) @NoPostgresqlNul String databaseType,
        @Pattern(regexp = "(?!(?:.*/)?\\.\\.(?:/|$))[A-Za-z0-9][A-Za-z0-9._/-]{0,255}")
        @NoPostgresqlNul String logicalLocation,
        @NotNull BackupStatus status,
        @NotNull Instant startedAt,
        Instant finishedAt,
        @PositiveOrZero Long sizeBytes,
        Instant expiresAt,
        @Size(max = 1024) @NoPostgresqlNul String failureSummary,
        Instant restoreTestedAt,
        @Size(max = 24) @NoPostgresqlNul String restoreTestStatus) {

    @AssertTrue(message = "Backup timestamps must be within PostgreSQL's supported range")
    public boolean isPostgresqlTimestampRangeSupported() {
        return isSupportedIfPresent(startedAt) && isSupportedIfPresent(finishedAt)
                && isSupportedIfPresent(expiresAt) && isSupportedIfPresent(restoreTestedAt);
    }

    private static boolean isSupportedIfPresent(Instant timestamp) {
        return timestamp == null || PostgresqlTimestampRange.isSupported(timestamp);
    }

    public enum BackupStatus {
        RUNNING, SUCCESS, FAILED, INCOMPLETE
    }
}
