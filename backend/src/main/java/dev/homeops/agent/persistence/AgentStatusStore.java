package dev.homeops.agent.persistence;

import dev.homeops.common.PostgresqlTimestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentStatusStore {
    private final JdbcTemplate jdbcTemplate;

    public AgentStatusStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insertIfAbsent(
            String agentId,
            UUID snapshotId,
            String version,
            Instant capturedAt,
            Instant receivedAt) {
        return jdbcTemplate.update("""
                INSERT INTO agent_status
                    (agent_id, agent_version, status, last_snapshot_id, last_captured_at, last_seen_at)
                VALUES (?, ?, 'CONNECTED', ?, ?, ?)
                ON CONFLICT (agent_id) DO NOTHING
                """, agentId, version, snapshotId, PostgresqlTimestamp.toTimestamp(capturedAt),
                PostgresqlTimestamp.toTimestamp(receivedAt)) == 1;
    }

    public boolean updateIfCapturedAtIsNotOlder(
            String agentId,
            UUID snapshotId,
            String version,
            Instant capturedAt,
            Instant receivedAt) {
        return jdbcTemplate.update("""
                UPDATE agent_status
                SET agent_version = ?,
                    status = 'CONNECTED',
                    last_snapshot_id = ?,
                    last_captured_at = ?,
                    last_seen_at = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE agent_id = ?
                  AND (last_captured_at IS NULL OR last_captured_at <= ?)
                """, version, snapshotId, PostgresqlTimestamp.toTimestamp(capturedAt),
                PostgresqlTimestamp.toTimestamp(receivedAt), agentId,
                PostgresqlTimestamp.toTimestamp(capturedAt)) == 1;
    }
}
