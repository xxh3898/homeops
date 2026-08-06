package dev.homeops.activity;

import dev.homeops.activity.api.ActivityEventResponse;
import dev.homeops.activity.api.ActivityEventResponse.Severity;
import dev.homeops.activity.api.ActivityEventResponse.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
                       d.started_at AS occurred_at,
                       substring(d.commit_sha FROM 1 FOR 12) AS context,
                       'DEPLOYMENT:' || CAST(d.id AS text) AS sort_key
                FROM deployment d
                UNION ALL
                SELECT CAST(b.id AS text), 'BACKUP', b.project || ' backup', b.status,
                       CASE WHEN b.status IN ('FAILED', 'INCOMPLETE') THEN 'CRITICAL'
                            WHEN b.status = 'RUNNING' THEN 'WARNING' ELSE 'INFO' END,
                       b.started_at, b.database_type,
                       'BACKUP:' || CAST(b.id AS text)
                FROM backup_run b
                UNION ALL
                SELECT CAST(i.id AS text), 'INCIDENT', i.title, i.status,
                       CASE WHEN i.status = 'RESOLVED' THEN 'RECOVERY' ELSE i.severity END,
                       CASE WHEN i.status = 'RESOLVED' THEN COALESCE(i.resolved_at, i.opened_at)
                            ELSE i.opened_at END,
                       COALESCE(s.name, i.incident_type), 'INCIDENT:' || CAST(i.id AS text)
                FROM incident i LEFT JOIN monitored_service s ON s.id = i.service_id
                UNION ALL
                SELECT CAST(a.id AS text), 'AGENT', a.summary, a.event_type, 'INFO',
                       a.occurred_at, a.agent_version, 'AGENT:' || CAST(a.id AS text)
                FROM agent_event a
            ) events
            WHERE occurred_at <= ?
              AND (?::timestamptz IS NULL OR (occurred_at, sort_key) < (?::timestamptz, ?))
            ORDER BY occurred_at DESC, sort_key DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public ActivityStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<StoredActivity> find(ActivityCursor cursor, Instant snapshotAt, int limit) {
        Timestamp before = cursor == null ? null : Timestamp.from(cursor.occurredAt());
        String sortKey = cursor == null ? "" : cursor.sortKey();
        return jdbcTemplate.query(ACTIVITY_QUERY, ActivityStore::mapActivity,
                Timestamp.from(snapshotAt), before, before, sortKey, limit);
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
