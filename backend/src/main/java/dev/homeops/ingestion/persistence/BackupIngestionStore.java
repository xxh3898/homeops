package dev.homeops.ingestion.persistence;

import dev.homeops.ingestion.api.BackupIngestionRequest;
import dev.homeops.common.PostgresqlTimestamp;
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

    public Optional<UUID> insertIfAbsent(BackupIngestionRequest request, String digest) {
        UUID id = UUID.randomUUID();
        return jdbcTemplate.query("""
                INSERT INTO backup_run (id, event_key, project, database_type, logical_location, status, started_at,
                    finished_at, size_bytes, expires_at, failure_summary, restore_tested_at, restore_test_status,
                    ingestion_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_key) DO NOTHING
                RETURNING id
                """, (row, index) -> row.getObject("id", UUID.class), id, request.eventKey(),
                request.project(), request.databaseType(), request.logicalLocation(),
                request.status().name(), timestamp(request.startedAt()), timestamp(request.finishedAt()),
                request.sizeBytes(), timestamp(request.expiresAt()), request.failureSummary(),
                timestamp(request.restoreTestedAt()), request.restoreTestStatus(), digest)
                .stream()
                .findFirst();
    }

    public boolean update(BackupIngestionRequest request, String digest,
            BackupIngestionRequest.BackupStatus expectedStatus) {
        return jdbcTemplate.update("""
                UPDATE backup_run SET logical_location = ?, status = ?, finished_at = ?, size_bytes = ?,
                    expires_at = ?, failure_summary = ?, restore_tested_at = ?, restore_test_status = ?,
                    ingestion_digest = ?
                WHERE event_key = ? AND status = ?
                """, request.logicalLocation(), request.status().name(), timestamp(request.finishedAt()),
                request.sizeBytes(), timestamp(request.expiresAt()), request.failureSummary(),
                timestamp(request.restoreTestedAt()), request.restoreTestStatus(), digest, request.eventKey(),
                expectedStatus.name()) == 1;
    }

    private static Timestamp timestamp(Instant value) { return PostgresqlTimestamp.toTimestamp(value); }

    public record StoredBackup(UUID id, String project, String databaseType, Instant startedAt,
            String status, String digest) {
        public boolean matchesLifecycle(BackupIngestionRequest request) {
            return project.equals(request.project()) && databaseType.equals(request.databaseType())
                    && sameDatabaseInstant(startedAt, request.startedAt());
        }
    }

    private static boolean sameDatabaseInstant(Instant stored, Instant requested) {
        return PostgresqlTimestamp.canonicalize(stored).equals(PostgresqlTimestamp.canonicalize(requested));
    }
}
