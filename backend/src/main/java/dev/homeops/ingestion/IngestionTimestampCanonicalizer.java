package dev.homeops.ingestion;

import dev.homeops.common.PostgresqlTimestamp;
import dev.homeops.ingestion.api.BackupIngestionRequest;
import dev.homeops.ingestion.api.DeploymentIngestionRequest;

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
}
