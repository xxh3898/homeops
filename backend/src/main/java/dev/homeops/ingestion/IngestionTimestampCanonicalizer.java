package dev.homeops.ingestion;

import dev.homeops.common.PostgresqlTimestamp;
import dev.homeops.ingestion.api.BackupIngestionRequest;
import dev.homeops.ingestion.api.DeploymentIngestionRequest;
import dev.homeops.ingestion.api.SignalIngestionRequest;

final class IngestionTimestampCanonicalizer {
    private IngestionTimestampCanonicalizer() { }

    static DeploymentIngestionRequest canonicalize(DeploymentIngestionRequest request) {
        return new DeploymentIngestionRequest(request.eventKey(), request.project(), request.environment(), request.branch(),
                request.commitSha(), request.imageTag(), request.previousCommitSha(), request.status(),
                PostgresqlTimestamp.canonicalize(request.startedAt()),
                PostgresqlTimestamp.canonicalize(request.finishedAt()), request.failureStage(), request.failureSummary(),
                request.actor(), request.workflowRunId(), request.workflowRunUrl(), request.rollback());
    }

    static BackupIngestionRequest canonicalize(BackupIngestionRequest request) {
        return new BackupIngestionRequest(request.eventKey(), request.project(), request.databaseType(),
                request.logicalLocation(), request.status(), PostgresqlTimestamp.canonicalize(request.startedAt()),
                PostgresqlTimestamp.canonicalize(request.finishedAt()), request.sizeBytes(),
                PostgresqlTimestamp.canonicalize(request.expiresAt()), request.failureSummary(),
                PostgresqlTimestamp.canonicalize(request.restoreTestedAt()), request.restoreTestStatus());
    }

    static SignalIngestionRequest canonicalize(SignalIngestionRequest request) {
        return new SignalIngestionRequest(request.eventKey(), request.episodeKey(), request.project(),
                request.signalType(), request.status(), PostgresqlTimestamp.canonicalize(request.observedAt()),
                canonicalDecimal(request.availablePercent()), canonicalDecimal(request.thresholdPercent()),
                request.count(), request.windowSeconds(), request.thresholdCount());
    }

    private static java.math.BigDecimal canonicalDecimal(java.math.BigDecimal value) {
        if (value == null || value.signum() == 0) {
            return value == null ? null : java.math.BigDecimal.ZERO;
        }
        return value.stripTrailingZeros();
    }
}
