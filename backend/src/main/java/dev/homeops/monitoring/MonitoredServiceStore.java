package dev.homeops.monitoring;

import dev.homeops.common.DuplicateMonitoredServiceNameException;
import dev.homeops.monitoring.api.MonitoredServiceRequest;
import dev.homeops.monitoring.api.MonitoredServiceResponse;
import dev.homeops.monitoring.api.IncidentResponse;
import dev.homeops.monitoring.api.ServiceStatusResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MonitoredServiceStore {
    private final JdbcTemplate jdbc;

    public MonitoredServiceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public MonitoredServiceResponse create(MonitoredServiceRequest request) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO monitored_service
                    (id, name, url, http_method, expected_status, timeout_ms, interval_seconds,
                     failure_threshold, recovery_threshold, severity, enabled, notification_enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT uk_monitored_service_name DO NOTHING
                """, id, request.name(), request.url(), request.method().name(),
                request.expectedStatus(), request.timeoutMs(), request.intervalSeconds(),
                request.failureThreshold(), request.recoveryThreshold(), request.severity().name(),
                request.enabled(), request.notificationEnabled());
        if (inserted == 0) throw new DuplicateMonitoredServiceNameException();
        return response(id, request);
    }

    public List<MonitoredServiceResponse> list() {
        return jdbc.query("""
                SELECT id, name, url, http_method, expected_status, timeout_ms, interval_seconds,
                       failure_threshold, recovery_threshold, severity, enabled, notification_enabled
                FROM monitored_service ORDER BY name
                """, MonitoredServiceStore::mapService);
    }

    public List<MonitoredServiceResponse> findDue(Instant now) {
        return jdbc.query("""
                SELECT s.id, s.name, s.url, s.http_method, s.expected_status, s.timeout_ms,
                       s.interval_seconds, s.failure_threshold, s.recovery_threshold, s.severity,
                       s.enabled, s.notification_enabled
                FROM monitored_service s
                WHERE s.enabled = TRUE
                  AND NOT EXISTS (
                    SELECT 1 FROM health_check_result r
                    WHERE r.service_id = s.id
                      AND r.checked_at > CAST(? AS timestamptz)
                          - make_interval(secs => s.interval_seconds)
                  )
                ORDER BY s.name
                """, MonitoredServiceStore::mapService, Timestamp.from(now));
    }

    private static MonitoredServiceResponse response(UUID id, MonitoredServiceRequest request) {
        return new MonitoredServiceResponse(id, request.name(), request.url(), request.method().name(),
                request.expectedStatus(), request.timeoutMs(), request.intervalSeconds(),
                request.failureThreshold(), request.recoveryThreshold(), request.severity().name(),
                request.enabled(), request.notificationEnabled());
    }

    public void recordResult(UUID serviceId, Instant checkedAt, HttpServiceChecker.Result result) {
        jdbc.update("""
                INSERT INTO health_check_result
                    (id, service_id, checked_at, status, http_status, response_time_ms, error_code)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), serviceId, Timestamp.from(checkedAt),
                result.healthy() ? "HEALTHY" : "DOWN", result.httpStatus(),
                Math.toIntExact(Math.min(result.responseTimeMs(), Integer.MAX_VALUE)), result.errorCode());
    }

    public int consecutiveStatusCount(UUID serviceId, String status) {
        return jdbc.query("""
                SELECT status FROM health_check_result
                WHERE service_id = ? ORDER BY checked_at DESC LIMIT 100
                """, (row, index) -> row.getString(1), serviceId)
                .stream()
                .takeWhile(status::equals)
                .mapToInt(ignored -> 1)
                .sum();
    }

    public Optional<OpenIncident> findOpenIncident(UUID serviceId) {
        return jdbc.query("""
                SELECT id, status FROM incident
                WHERE service_id = ? AND status IN ('OPEN', 'ACKNOWLEDGED')
                ORDER BY opened_at DESC LIMIT 1
                """, (row, index) -> new OpenIncident(
                row.getObject("id", UUID.class), row.getString("status")), serviceId).stream().findFirst();
    }

    public void openIncident(MonitoredServiceResponse service, Instant now) {
        jdbc.update("""
                INSERT INTO incident
                    (id, service_id, incident_type, severity, status, title, opened_at, last_observed_at)
                VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?)
                """, UUID.randomUUID(), service.id(), "HEALTH_CHECK", service.severity(),
                service.name() + " is unavailable", Timestamp.from(now), Timestamp.from(now));
    }

    public void observeIncident(UUID incidentId, Instant now) {
        jdbc.update("UPDATE incident SET last_observed_at = ? WHERE id = ?",
                Timestamp.from(now), incidentId);
    }

    public void resolveIncident(UUID incidentId, Instant now) {
        jdbc.update("""
                UPDATE incident SET status = 'RESOLVED', resolved_at = ?, last_observed_at = ?,
                    resolved_xid = pg_current_xact_id()
                WHERE id = ? AND status IN ('OPEN', 'ACKNOWLEDGED')
                """, Timestamp.from(now), Timestamp.from(now), incidentId);
    }

    public int deleteResultsOlderThan(String status, Instant threshold) {
        return jdbc.update("""
                DELETE FROM health_check_result result
                WHERE result.status = ? AND result.checked_at < ?
                  AND EXISTS (
                    SELECT 1 FROM health_check_result later
                    WHERE later.service_id = result.service_id
                      AND later.status <> result.status
                      AND later.checked_at > result.checked_at
                  )
                """,
                status, Timestamp.from(threshold));
    }

    public List<IncidentResponse> recentIncidents() {
        return jdbc.query("""
                SELECT id, service_id, severity, status, title, opened_at, resolved_at, last_observed_at
                FROM incident ORDER BY opened_at DESC LIMIT 100
                """, (row, index) -> new IncidentResponse(
                row.getObject("id", UUID.class), row.getObject("service_id", UUID.class),
                row.getString("severity"), row.getString("status"), row.getString("title"),
                row.getTimestamp("opened_at").toInstant(),
                row.getTimestamp("resolved_at") == null ? null : row.getTimestamp("resolved_at").toInstant(),
                row.getTimestamp("last_observed_at").toInstant()));
    }

    public List<ServiceStatusResponse> currentStatuses() {
        return jdbc.query("""
                SELECT s.id, s.name, s.enabled, r.status, r.checked_at, r.http_status,
                       r.response_time_ms,
                       EXISTS (SELECT 1 FROM incident i WHERE i.service_id = s.id
                           AND i.status IN ('OPEN', 'ACKNOWLEDGED')) AS incident_open
                FROM monitored_service s
                LEFT JOIN LATERAL (
                    SELECT status, checked_at, http_status, response_time_ms
                    FROM health_check_result
                    WHERE service_id = s.id ORDER BY checked_at DESC LIMIT 1
                ) r ON TRUE
                ORDER BY s.name
                """, (row, index) -> new ServiceStatusResponse(
                row.getObject("id", UUID.class), row.getString("name"), row.getBoolean("enabled"),
                row.getString("status") == null ? (row.getBoolean("enabled") ? "UNKNOWN" : "DISABLED")
                        : row.getString("status"),
                row.getTimestamp("checked_at") == null ? null : row.getTimestamp("checked_at").toInstant(),
                (Integer) row.getObject("http_status"), (Integer) row.getObject("response_time_ms"),
                row.getBoolean("incident_open")));
    }

    private static MonitoredServiceResponse mapService(java.sql.ResultSet row, int index)
            throws java.sql.SQLException {
        return new MonitoredServiceResponse(row.getObject("id", UUID.class), row.getString("name"),
                row.getString("url"), row.getString("http_method"), row.getInt("expected_status"),
                row.getInt("timeout_ms"), row.getInt("interval_seconds"), row.getInt("failure_threshold"),
                row.getInt("recovery_threshold"), row.getString("severity"), row.getBoolean("enabled"),
                row.getBoolean("notification_enabled"));
    }

    public record OpenIncident(UUID id, String status) { }
}
