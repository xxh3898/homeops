package dev.homeops.ingestion.persistence;

import dev.homeops.ingestion.api.DeploymentIngestionRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeploymentIngestionStore {
    private final JdbcTemplate jdbcTemplate;

    public DeploymentIngestionStore(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public Optional<StoredDeployment> find(String eventKey) {
        return jdbcTemplate.query("""
                SELECT id, project, environment, commit_sha, started_at, status, ingestion_digest
                FROM deployment WHERE event_key = ?
                """, (row, index) -> new StoredDeployment(
                        row.getObject("id", UUID.class), row.getString("project"), row.getString("environment"),
                        row.getString("commit_sha").strip(), row.getTimestamp("started_at").toInstant(),
                        row.getString("status"), row.getString("ingestion_digest")), eventKey)
                .stream().findFirst();
    }

    public Optional<UUID> insertIfAbsent(DeploymentIngestionRequest request, String digest) {
        UUID id = UUID.randomUUID();
        return jdbcTemplate.query("""
                INSERT INTO deployment (id, event_key, project, environment, branch, commit_sha, image_tag,
                    previous_commit_sha, status, started_at, finished_at, failure_stage, failure_summary, actor,
                    workflow_run_id, workflow_run_url, rollback, ingestion_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_key) DO NOTHING
                RETURNING id
                """, (row, index) -> row.getObject("id", UUID.class), id, request.eventKey(),
                request.project(), request.environment(), request.branch(), request.commitSha(),
                request.imageTag(), request.previousCommitSha(), request.status().name(), Timestamp.from(request.startedAt()),
                timestamp(request.finishedAt()), request.failureStage(), request.failureSummary(), request.actor(),
                request.workflowRunId(), request.workflowRunUrl(), request.rollback(), digest)
                .stream()
                .findFirst();
    }

    public void update(DeploymentIngestionRequest request, String digest) {
        jdbcTemplate.update("""
                UPDATE deployment SET branch = ?, image_tag = ?, previous_commit_sha = ?, status = ?,
                    finished_at = ?, failure_stage = ?, failure_summary = ?, actor = ?, workflow_run_id = ?,
                    workflow_run_url = ?, rollback = ?, ingestion_digest = ? WHERE event_key = ?
                """, request.branch(), request.imageTag(), request.previousCommitSha(), request.status().name(),
                timestamp(request.finishedAt()), request.failureStage(), request.failureSummary(), request.actor(),
                request.workflowRunId(), request.workflowRunUrl(), request.rollback(), digest, request.eventKey());
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    public record StoredDeployment(UUID id, String project, String environment, String commitSha,
            Instant startedAt, String status, String digest) {
        public boolean matchesLifecycle(DeploymentIngestionRequest request) {
            return project.equals(request.project()) && environment.equals(request.environment())
                    && commitSha.equals(request.commitSha()) && sameDatabaseInstant(startedAt, request.startedAt());
        }
    }

    private static boolean sameDatabaseInstant(Instant stored, Instant requested) {
        return stored.truncatedTo(ChronoUnit.MICROS)
                .equals(requested.truncatedTo(ChronoUnit.MICROS));
    }
}
