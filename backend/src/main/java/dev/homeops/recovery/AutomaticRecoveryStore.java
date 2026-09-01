package dev.homeops.recovery;

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
class AutomaticRecoveryStore {
    private final JdbcTemplate jdbc;

    AutomaticRecoveryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AutomaticRecoveryAttempt> findByIncident(UUID incidentId) {
        return queryAttempt("""
                SELECT id, incident_id, service_id, project, target, action, status,
                       reason_code, requested_at, dispatched_at, started_at, completed_at,
                       pre_health, post_health, restart_count
                FROM automatic_recovery_attempt
                WHERE incident_id = ?
                """, incidentId);
    }

    Optional<AutomaticRecoveryAttempt> findById(UUID attemptId) {
        return queryAttempt("""
                SELECT id, incident_id, service_id, project, target, action, status,
                       reason_code, requested_at, dispatched_at, started_at, completed_at,
                       pre_health, post_health, restart_count
                FROM automatic_recovery_attempt
                WHERE id = ?
                """, attemptId);
    }

    boolean incidentOpenForUpdate(UUID incidentId, UUID serviceId) {
        return jdbc.query("""
                SELECT status
                FROM incident
                WHERE id = ? AND service_id = ?
                FOR UPDATE
                """, (row, index) -> row.getString("status"), incidentId, serviceId)
                .stream()
                .findFirst()
                .filter(status -> status.equals("OPEN") || status.equals("ACKNOWLEDGED"))
                .isPresent();
    }

    Optional<AutomaticRecoveryMapping> findMappingForUpdate(UUID serviceId) {
        return jdbc.query("""
                SELECT service_id, project, target, enabled, last_reserved_at
                FROM automatic_recovery_mapping
                WHERE service_id = ?
                FOR UPDATE
                """, AutomaticRecoveryStore::mapMapping, serviceId)
                .stream()
                .findFirst();
    }

    boolean insertRequested(
            UUID attemptId,
            UUID incidentId,
            UUID serviceId,
            AutomaticRecoveryMapping mapping,
            Instant requestedAt) {
        return insertAttempt(
                attemptId,
                incidentId,
                serviceId,
                mapping.project(),
                mapping.target(),
                AutomaticRecoveryStatus.REQUESTED,
                null,
                requestedAt,
                null,
                null,
                null,
                null);
    }

    boolean insertSkipped(
            UUID attemptId,
            UUID incidentId,
            UUID serviceId,
            AutomaticRecoveryMapping mapping,
            AutomaticRecoveryReasonCode reason,
            Instant requestedAt) {
        return insertAttempt(
                attemptId,
                incidentId,
                serviceId,
                mapping == null ? null : mapping.project(),
                mapping == null ? null : mapping.target(),
                AutomaticRecoveryStatus.SKIPPED,
                reason,
                requestedAt,
                requestedAt,
                AutomaticRecoveryHealth.UNKNOWN,
                AutomaticRecoveryHealth.UNKNOWN,
                0);
    }

    boolean updateLastReservedAt(UUID serviceId, Instant reservedAt) {
        return jdbc.update("""
                UPDATE automatic_recovery_mapping
                SET last_reserved_at = ?, updated_at = CURRENT_TIMESTAMP
                WHERE service_id = ?
                """, PostgresqlTimestamp.toTimestamp(reservedAt), serviceId) == 1;
    }

    Optional<AutomaticRecoveryAttempt> findNextRequestedForUpdate() {
        return jdbc.query("""
                SELECT a.id, a.incident_id, a.service_id, a.project, a.target,
                       a.action, a.status, a.reason_code, a.requested_at,
                       a.dispatched_at, a.started_at, a.completed_at,
                       a.pre_health, a.post_health, a.restart_count
                FROM automatic_recovery_attempt a
                WHERE a.status = 'REQUESTED'
                ORDER BY a.requested_at, a.id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """, AutomaticRecoveryStore::mapAttempt)
                .stream()
                .findFirst();
    }

    boolean markDispatched(UUID attemptId, Instant dispatchedAt) {
        return jdbc.update("""
                UPDATE automatic_recovery_attempt
                SET status = 'DISPATCHED', dispatched_at = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, PostgresqlTimestamp.toTimestamp(dispatchedAt), attemptId) == 1;
    }

    boolean completeRequested(
            UUID attemptId,
            AutomaticRecoveryStatus status,
            AutomaticRecoveryReasonCode reason,
            Instant completedAt) {
        if (!status.terminal()) {
            throw new IllegalArgumentException("Terminal automatic recovery status is required");
        }
        return jdbc.update("""
                UPDATE automatic_recovery_attempt
                SET status = ?, reason_code = ?, completed_at = ?,
                    pre_health = 'UNKNOWN', post_health = 'UNKNOWN', restart_count = 0
                WHERE id = ? AND status = 'REQUESTED'
                """, status.name(), reason.name(), PostgresqlTimestamp.toTimestamp(completedAt), attemptId) == 1;
    }

    boolean completeDispatched(UUID attemptId, AutomaticRecoveryResult result) {
        AutomaticRecoveryStatus projected = switch (result.status()) {
            case APPLIED -> AutomaticRecoveryStatus.APPLIED;
            case NOOP -> AutomaticRecoveryStatus.SKIPPED;
            case FAILED -> AutomaticRecoveryStatus.FAILED;
            case OUTCOME_UNKNOWN -> AutomaticRecoveryStatus.OUTCOME_UNKNOWN;
            case EXPIRED -> AutomaticRecoveryStatus.EXPIRED;
        };
        return jdbc.update("""
                UPDATE automatic_recovery_attempt
                SET status = ?, reason_code = ?, started_at = ?, completed_at = ?,
                    pre_health = ?, post_health = ?, restart_count = ?
                WHERE id = ? AND status = 'DISPATCHED'
                """,
                projected.name(),
                result.reasonCode().name(),
                PostgresqlTimestamp.toTimestamp(result.startedAt()),
                PostgresqlTimestamp.toTimestamp(result.finishedAt()),
                result.preHealth().name(),
                result.postHealth().name(),
                result.restartCount(),
                attemptId) == 1;
    }

    boolean completeDispatchedWithoutResult(
            UUID attemptId,
            AutomaticRecoveryStatus status,
            AutomaticRecoveryReasonCode reason,
            Instant completedAt) {
        if (!status.terminal()) {
            throw new IllegalArgumentException("Terminal automatic recovery status is required");
        }
        return jdbc.update("""
                UPDATE automatic_recovery_attempt
                SET status = ?, reason_code = ?, completed_at = ?,
                    pre_health = 'UNKNOWN', post_health = 'UNKNOWN', restart_count = 0
                WHERE id = ? AND status = 'DISPATCHED'
                """, status.name(), reason.name(), PostgresqlTimestamp.toTimestamp(completedAt), attemptId) == 1;
    }

    int reconcileStaleDispatched(Instant cutoff, Instant completedAt, int batchSize) {
        return jdbc.update("""
                WITH stale AS (
                    SELECT id
                    FROM automatic_recovery_attempt
                    WHERE status = 'DISPATCHED' AND dispatched_at <= ?
                    ORDER BY dispatched_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE automatic_recovery_attempt attempt
                SET status = 'OUTCOME_UNKNOWN',
                    reason_code = 'RESULT_UNAVAILABLE',
                    completed_at = ?,
                    pre_health = COALESCE(pre_health, 'UNKNOWN'),
                    post_health = COALESCE(post_health, 'UNKNOWN')
                FROM stale
                WHERE attempt.id = stale.id AND attempt.status = 'DISPATCHED'
                """,
                PostgresqlTimestamp.toTimestamp(cutoff),
                batchSize,
                PostgresqlTimestamp.toTimestamp(completedAt));
    }

    private boolean insertAttempt(
            UUID attemptId,
            UUID incidentId,
            UUID serviceId,
            AutomaticRecoveryProject project,
            AutomaticRecoveryTarget target,
            AutomaticRecoveryStatus status,
            AutomaticRecoveryReasonCode reason,
            Instant requestedAt,
            Instant completedAt,
            AutomaticRecoveryHealth preHealth,
            AutomaticRecoveryHealth postHealth,
            Integer restartCount) {
        return jdbc.update("""
                INSERT INTO automatic_recovery_attempt (
                    id, incident_id, service_id, project, target, action, status,
                    reason_code, requested_at, completed_at, pre_health, post_health,
                    restart_count)
                VALUES (?, ?, ?, ?, ?, 'RESTART', ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (incident_id) DO NOTHING
                """,
                attemptId,
                incidentId,
                serviceId,
                project == null ? null : project.wireValue(),
                target == null ? null : target.wireValue(),
                status.name(),
                reason == null ? null : reason.name(),
                PostgresqlTimestamp.toTimestamp(requestedAt),
                PostgresqlTimestamp.toTimestamp(completedAt),
                preHealth == null ? null : preHealth.name(),
                postHealth == null ? null : postHealth.name(),
                restartCount) == 1;
    }

    private Optional<AutomaticRecoveryAttempt> queryAttempt(String sql, UUID identifier) {
        List<AutomaticRecoveryAttempt> rows = jdbc.query(sql, AutomaticRecoveryStore::mapAttempt, identifier);
        return rows.stream().findFirst();
    }

    private static AutomaticRecoveryMapping mapMapping(ResultSet row, int index) throws SQLException {
        java.sql.Timestamp lastReservedAt = row.getTimestamp("last_reserved_at");
        return new AutomaticRecoveryMapping(
                row.getObject("service_id", UUID.class),
                AutomaticRecoveryProject.fromWireValue(row.getString("project")),
                AutomaticRecoveryTarget.fromWireValue(row.getString("target")),
                row.getBoolean("enabled"),
                lastReservedAt == null ? null : lastReservedAt.toInstant());
    }

    private static AutomaticRecoveryAttempt mapAttempt(ResultSet row, int index) throws SQLException {
        String project = row.getString("project");
        String target = row.getString("target");
        String reason = row.getString("reason_code");
        String preHealth = row.getString("pre_health");
        String postHealth = row.getString("post_health");
        return new AutomaticRecoveryAttempt(
                row.getObject("id", UUID.class),
                row.getObject("incident_id", UUID.class),
                row.getObject("service_id", UUID.class),
                project == null ? null : AutomaticRecoveryProject.fromWireValue(project),
                target == null ? null : AutomaticRecoveryTarget.fromWireValue(target),
                AutomaticRecoveryAction.valueOf(row.getString("action")),
                AutomaticRecoveryStatus.valueOf(row.getString("status")),
                reason == null ? null : AutomaticRecoveryReasonCode.valueOf(reason),
                row.getTimestamp("requested_at").toInstant(),
                instant(row, "dispatched_at"),
                instant(row, "started_at"),
                instant(row, "completed_at"),
                preHealth == null ? null : AutomaticRecoveryHealth.valueOf(preHealth),
                postHealth == null ? null : AutomaticRecoveryHealth.valueOf(postHealth),
                (Integer) row.getObject("restart_count"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        java.sql.Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
