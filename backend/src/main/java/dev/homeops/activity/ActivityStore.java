package dev.homeops.activity;

import dev.homeops.activity.api.ActivityEventResponse;
import dev.homeops.activity.api.ActivityEventResponse.Severity;
import dev.homeops.activity.api.ActivityEventResponse.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ActivityStore {
    private static final String ACTIVITY_QUERY = """
            SELECT id, event_type, title, status, severity, occurred_at, context, sort_key
            FROM (
                SELECT CAST(d.id AS text) AS id, 'DEPLOYMENT' AS event_type,
                       d.project || ' deployment' AS title, d.status,
                       CASE WHEN d.status IN ('FAILED', 'ROLLED_BACK') THEN 'CRITICAL'
                            WHEN d.status IN ('RUNNING', 'REQUESTED') THEN 'WARNING' ELSE 'INFO' END AS severity,
                       d.started_at AS occurred_at, d.recorded_xid AS recorded_xid,
                       substring(d.commit_sha FROM 1 FOR 12) AS context,
                       'DEPLOYMENT:' || CAST(d.id AS text) AS sort_key
                FROM deployment d
                UNION ALL
                SELECT CAST(b.id AS text), 'BACKUP', b.project || ' backup', b.status,
                       CASE WHEN b.status IN ('FAILED', 'INCOMPLETE') THEN 'CRITICAL'
                            WHEN b.status = 'RUNNING' THEN 'WARNING' ELSE 'INFO' END,
                       b.started_at, b.recorded_xid, b.database_type,
                       'BACKUP:' || CAST(b.id AS text)
                FROM backup_run b
                UNION ALL
                SELECT CAST(i.id AS text), 'INCIDENT', i.title, 'OPEN', i.severity,
                       i.opened_at, i.recorded_xid,
                       COALESCE(s.name, i.incident_type), 'INCIDENT_OPEN:' || CAST(i.id AS text)
                FROM incident i LEFT JOIN monitored_service s ON s.id = i.service_id
                UNION ALL
                SELECT CAST(i.id AS text), 'INCIDENT', i.title, 'RESOLVED', 'RECOVERY',
                       i.resolved_at, COALESCE(i.resolved_xid, i.recorded_xid),
                       COALESCE(s.name, i.incident_type), 'INCIDENT_RECOVERY:' || CAST(i.id AS text)
                FROM incident i LEFT JOIN monitored_service s ON s.id = i.service_id
                WHERE i.resolved_at IS NOT NULL
                UNION ALL
                SELECT CAST(a.id AS text), 'AGENT', a.summary, a.event_type, 'INFO',
                       a.occurred_at, a.recorded_xid, a.agent_version, 'AGENT:' || CAST(a.id AS text)
                FROM agent_event a
                UNION ALL
                SELECT CAST(c.id AS text), 'CONTAINER_ACTION',
                       CASE c.action
                           WHEN 'START' THEN 'Container start'
                           WHEN 'STOP' THEN 'Container stop'
                           WHEN 'RESTART' THEN 'Container restart'
                           ELSE 'Container action'
                       END,
                       c.result,
                       CASE WHEN c.result IN ('APPLIED', 'NOOP') THEN 'INFO'
                            WHEN c.result IN ('REQUESTED', 'DENIED', 'EXPIRED') THEN 'WARNING'
                            ELSE 'CRITICAL' END,
                       c.requested_at, c.recorded_xid, c.container_id_prefix,
                       'CONTAINER_ACTION:' || CAST(c.id AS text)
                FROM container_action_audit c
                WHERE c.container_id_prefix ~ '^[0-9a-f]{12}$'
                UNION ALL
                SELECT CAST(r.id AS text), 'CONTAINER_ACTION',
                       'Automatic recovery restart', r.status,
                       CASE WHEN r.status IN ('APPLIED', 'SKIPPED') THEN 'INFO'
                            WHEN r.status IN ('REQUESTED', 'DISPATCHED', 'EXPIRED') THEN 'WARNING'
                            ELSE 'CRITICAL' END,
                       r.requested_at, r.recorded_xid,
                       CASE
                           WHEN r.project = 'rhaomi'
                                AND r.target IN ('rhaomi-web', 'backend')
                           THEN r.project || '/' || r.target
                           ELSE 'unmapped'
                       END,
                       'AUTOMATIC_RECOVERY:' || CAST(r.id AS text)
                FROM automatic_recovery_attempt r
            ) events
            WHERE pg_visible_in_snapshot(recorded_xid, ?::pg_snapshot)
              AND (?::text IS NULL OR event_type = ?::text)
              AND (?::timestamptz IS NULL OR (occurred_at, sort_key) < (?::timestamptz, ?))
            ORDER BY occurred_at DESC, sort_key DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public ActivityStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String currentVisibilitySnapshot() {
        return jdbcTemplate.queryForObject("SELECT pg_current_snapshot()::text", String.class);
    }

    public List<StoredActivity> find(
            ActivityCursor cursor, String visibilitySnapshot, ActivityTypeFilter type, int limit) {
        Timestamp before = cursor == null ? null : Timestamp.from(cursor.occurredAt());
        String sortKey = cursor == null ? "" : cursor.sortKey();
        String eventType = type.databaseValue();
        return jdbcTemplate.query(ACTIVITY_QUERY, ActivityStore::mapActivity,
                visibilitySnapshot, eventType, eventType, before, before, sortKey, limit);
    }

    private static StoredActivity mapActivity(ResultSet row, int index) throws SQLException {
        ActivityEventResponse response = new ActivityEventResponse(
                row.getString("id"), Type.valueOf(row.getString("event_type")), row.getString("title"),
                row.getString("status"), Severity.valueOf(row.getString("severity")),
                row.getTimestamp("occurred_at").toInstant(), row.getString("context"));
        return new StoredActivity(response, row.getString("sort_key"));
    }

    public record StoredActivity(ActivityEventResponse response, String sortKey) { }
}
