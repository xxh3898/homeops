package dev.homeops.notification;

import dev.homeops.agent.api.AgentSnapshotRequest.ContainerHealth;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerState;
import dev.homeops.common.PostgresqlTimestamp;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ContainerNotificationStateStore {
    private final JdbcTemplate jdbc;

    ContainerNotificationStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<ContainerNotificationState> findAllForUpdate(String agentId) {
        return jdbc.query("""
                SELECT id, agent_id, logical_identity_hash, display_name, compose_project,
                       instance_fingerprint, notifications_allowed, state, health,
                       last_snapshot_id, last_captured_at, failure_started_at,
                       active_episode_id, last_root_created_at
                FROM container_notification_state
                WHERE agent_id = ?
                ORDER BY logical_identity_hash
                FOR UPDATE
                """, (row, index) -> new ContainerNotificationState(
                row.getObject("id", UUID.class),
                row.getString("agent_id"),
                row.getString("logical_identity_hash"),
                row.getString("display_name"),
                row.getString("compose_project"),
                row.getString("instance_fingerprint"),
                row.getBoolean("notifications_allowed"),
                ContainerState.valueOf(row.getString("state")),
                ContainerHealth.valueOf(row.getString("health")),
                row.getObject("last_snapshot_id", UUID.class),
                row.getTimestamp("last_captured_at").toInstant(),
                instant(row.getTimestamp("failure_started_at")),
                row.getObject("active_episode_id", UUID.class),
                instant(row.getTimestamp("last_root_created_at"))), agentId);
    }

    void insert(ContainerNotificationState state) {
        int inserted = jdbc.update("""
                INSERT INTO container_notification_state (
                    id, agent_id, logical_identity_hash, display_name, compose_project,
                    instance_fingerprint, notifications_allowed, state, health,
                    last_snapshot_id, last_captured_at, failure_started_at,
                    active_episode_id, last_root_created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                state.id(),
                state.agentId(),
                state.logicalIdentityHash(),
                state.displayName(),
                state.composeProject(),
                state.instanceFingerprint(),
                state.notificationsAllowed(),
                state.state().name(),
                state.health().name(),
                state.lastSnapshotId(),
                PostgresqlTimestamp.toTimestamp(state.lastCapturedAt()),
                PostgresqlTimestamp.toTimestamp(state.failureStartedAt()),
                state.activeEpisodeId(),
                PostgresqlTimestamp.toTimestamp(state.lastRootCreatedAt()));
        if (inserted != 1) {
            throw new IllegalStateException("Container notification baseline was not inserted");
        }
    }

    void update(ContainerNotificationState state) {
        int updated = jdbc.update("""
                UPDATE container_notification_state
                SET display_name = ?,
                    compose_project = ?,
                    instance_fingerprint = ?,
                    notifications_allowed = ?,
                    state = ?,
                    health = ?,
                    last_snapshot_id = ?,
                    last_captured_at = ?,
                    failure_started_at = ?,
                    active_episode_id = ?,
                    last_root_created_at = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND last_captured_at < ?
                """,
                state.displayName(),
                state.composeProject(),
                state.instanceFingerprint(),
                state.notificationsAllowed(),
                state.state().name(),
                state.health().name(),
                state.lastSnapshotId(),
                PostgresqlTimestamp.toTimestamp(state.lastCapturedAt()),
                PostgresqlTimestamp.toTimestamp(state.failureStartedAt()),
                state.activeEpisodeId(),
                PostgresqlTimestamp.toTimestamp(state.lastRootCreatedAt()),
                state.id(),
                PostgresqlTimestamp.toTimestamp(state.lastCapturedAt()));
        if (updated != 1) {
            throw new IllegalStateException("Container notification state lost its update authority");
        }
    }

    void deleteMissingIfOlder(UUID id, Instant capturedAt) {
        jdbc.update("""
                DELETE FROM container_notification_state
                WHERE id = ? AND last_captured_at < ?
                """, id, PostgresqlTimestamp.toTimestamp(capturedAt));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
