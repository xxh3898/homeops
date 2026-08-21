package dev.homeops.agent.control;

import dev.homeops.common.PostgresqlTimestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ContainerActionAuditStore {
    private final JdbcTemplate jdbc;

    ContainerActionAuditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean insertRequested(
            UUID operationId,
            String idempotencyKey,
            String principal,
            String containerId,
            ContainerControlOperation operation,
            Instant requestedAt) {
        return jdbc.update("""
                INSERT INTO container_action_audit (
                    id, idempotency_key, requested_at, completed_at, principal,
                    action, container_id_prefix, container_name, image, result,
                    reason_code, failure_summary, metadata)
                VALUES (?, ?, ?, NULL, ?, ?, ?, NULL, NULL, 'REQUESTED',
                        NULL, NULL, '{}'::jsonb)
                ON CONFLICT (idempotency_key) DO NOTHING
                """,
                operationId,
                idempotencyKey,
                PostgresqlTimestamp.toTimestamp(requestedAt),
                principal,
                operation.name(),
                containerId) == 1;
    }

    Optional<ContainerActionAuditRecord> findByIdempotencyKey(String idempotencyKey) {
        return query("""
                SELECT id, idempotency_key, principal, container_id_prefix,
                       action, result, reason_code, requested_at, completed_at
                FROM container_action_audit
                WHERE idempotency_key = ?
                """, idempotencyKey);
    }

    Optional<ContainerActionAuditRecord> findById(UUID operationId) {
        return query("""
                SELECT id, idempotency_key, principal, container_id_prefix,
                       action, result, reason_code, requested_at, completed_at
                FROM container_action_audit
                WHERE id = ?
                """, operationId);
    }

    boolean completeRequested(
            UUID operationId,
            ContainerActionStatus status,
            String reasonCode,
            Instant completedAt) {
        if (!status.terminal()) {
            throw new IllegalArgumentException("Terminal container action status is required");
        }
        return jdbc.update("""
                UPDATE container_action_audit
                SET result = ?, reason_code = ?, completed_at = ?
                WHERE id = ? AND result = 'REQUESTED'
                """,
                status.name(),
                reasonCode,
                PostgresqlTimestamp.toTimestamp(completedAt),
                operationId) == 1;
    }

    int reconcileStaleRequested(
            Instant cutoff,
            Instant completedAt,
            int batchSize) {
        return jdbc.update("""
                WITH stale AS (
                    SELECT id
                    FROM container_action_audit
                    WHERE result = 'REQUESTED'
                      AND requested_at <= ?
                      AND container_id_prefix ~ '^[0-9a-f]{12}$'
                    ORDER BY requested_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE container_action_audit audit
                SET result = 'OUTCOME_UNKNOWN',
                    reason_code = 'RESULT_UNAVAILABLE',
                    completed_at = ?
                FROM stale
                WHERE audit.id = stale.id AND audit.result = 'REQUESTED'
                """,
                PostgresqlTimestamp.toTimestamp(cutoff),
                batchSize,
                PostgresqlTimestamp.toTimestamp(completedAt));
    }

    private Optional<ContainerActionAuditRecord> query(String sql, Object argument) {
        List<ContainerActionAuditRecord> rows = jdbc.query(
                sql,
                ContainerActionAuditStore::mapRecord,
                argument);
        return rows.stream().findFirst();
    }

    private static ContainerActionAuditRecord mapRecord(ResultSet row, int index)
            throws SQLException {
        java.sql.Timestamp completedAt = row.getTimestamp("completed_at");
        return new ContainerActionAuditRecord(
                row.getObject("id", UUID.class),
                row.getString("idempotency_key"),
                row.getString("principal"),
                row.getString("container_id_prefix"),
                ContainerControlOperation.valueOf(row.getString("action")),
                ContainerActionStatus.valueOf(row.getString("result")),
                row.getString("reason_code"),
                row.getTimestamp("requested_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant());
    }
}
