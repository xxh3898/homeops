package dev.homeops.ingestion.persistence;

import dev.homeops.ingestion.api.BackupIngestionRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BackupIngestionStore {
    private final JdbcTemplate jdbcTemplate;

    public BackupIngestionStore(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public Optional<StoredBackup> find(String eventKey) {
        return jdbcTemplate.query("""
                SELECT id, project, database_type, started_at, status, ingestion_digest
                FROM backup_run WHERE event_key = ?
                """, (row, index) -> new StoredBackup(row.getObject("id", UUID.class), row.getString("project"),
                row.getString("database_type"), row.getTimestamp("started_at").toInstant(),
                row.getString("status"), row.getString("ingestion_digest")), eventKey).stream().findFirst();
    }

    public UUID insert(BackupIngestionRequest request, String digest) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO backup_run (id, event_key, project, database_type, logical_location, status, started_at,
                    finished_at, size_bytes, expires_at, failure_summary, restore_tested_at, restore_test_status,
                    ingestion_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, request.eventKey(), request.project(), request.databaseType(), request.logicalLocation(),
                request.status().name(), Timestamp.from(request.startedAt()), timestamp(request.finishedAt()),
                request.sizeBytes(), timestamp(request.expiresAt()), request.failureSummary(),
                timestamp(request.restoreTestedAt()), request.restoreTestStatus(), digest);
        return id;
    }

    public void update(BackupIngestionRequest request, String digest) {
        jdbcTemplate.update("""
                UPDATE backup_run SET status = ?, finished_at = ?, size_bytes = ?, expires_at = ?,
                    failure_summary = ?, restore_tested_at = ?, restore_test_status = ?, ingestion_digest = ?
                WHERE event_key = ?
                """, request.status().name(), timestamp(request.finishedAt()), request.sizeBytes(),
                timestamp(request.expiresAt()), request.failureSummary(), timestamp(request.restoreTestedAt()),
                request.restoreTestStatus(), digest, request.eventKey());
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    public record StoredBackup(UUID id, String project, String databaseType, Instant startedAt,
            String status, String digest) {
        public boolean matchesLifecycle(BackupIngestionRequest request) {
            return project.equals(request.project()) && databaseType.equals(request.databaseType())
                    && startedAt.equals(request.startedAt());
        }
    }
}
