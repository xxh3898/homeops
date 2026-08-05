package dev.homeops.ingestion.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record BackupIngestionRequest(
        @NotBlank @Size(max = 128) String eventKey,
        @NotBlank @Size(max = 128) String project,
        @NotBlank @Size(max = 32) String databaseType,
        @Pattern(regexp = "(?!(?:.*/)?\\.\\.(?:/|$))[A-Za-z0-9][A-Za-z0-9._/-]{0,255}")
        String logicalLocation,
        @NotNull BackupStatus status,
        @NotNull Instant startedAt,
        Instant finishedAt,
        @PositiveOrZero Long sizeBytes,
        Instant expiresAt,
        @Size(max = 1024) String failureSummary,
        Instant restoreTestedAt,
        @Size(max = 24) String restoreTestStatus) {

    public enum BackupStatus {
        RUNNING, SUCCESS, FAILED, INCOMPLETE
    }
}
