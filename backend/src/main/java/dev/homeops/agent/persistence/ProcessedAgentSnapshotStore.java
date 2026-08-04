package dev.homeops.agent.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessedAgentSnapshotStore {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedAgentSnapshotStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean recordIfAbsent(
            String agentId,
            UUID snapshotId,
            Instant capturedAt,
            Instant processedAt) {
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO processed_agent_snapshot (
                    agent_id,
                    snapshot_id,
                    captured_at,
                    processed_at
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (agent_id, snapshot_id) DO NOTHING
                """,
                agentId,
                snapshotId,
                Timestamp.from(capturedAt),
                Timestamp.from(processedAt));
        return inserted == 1;
    }

    public Optional<Instant> findCapturedAt(
            String agentId,
            UUID snapshotId) {
        return jdbcTemplate.query(
                        """
                        SELECT captured_at
                        FROM processed_agent_snapshot
                        WHERE agent_id = ? AND snapshot_id = ?
                        """,
                        (resultSet, rowNumber) -> resultSet
                                .getTimestamp("captured_at")
                                .toInstant(),
                        agentId,
                        snapshotId)
                .stream()
                .findFirst();
    }

    public int deleteProcessedBefore(Instant cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM processed_agent_snapshot WHERE processed_at < ?",
                Timestamp.from(cutoff));
    }
}
