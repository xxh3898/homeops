package dev.homeops.monitoring;

import dev.homeops.monitoring.api.MonitoredServiceRequest;
import dev.homeops.monitoring.api.MonitoredServiceResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MonitoredServiceStore {
    private final JdbcTemplate jdbc;
    public MonitoredServiceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public MonitoredServiceResponse create(MonitoredServiceRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO monitored_service (id,name,url,http_method,expected_status,timeout_ms,interval_seconds,failure_threshold,recovery_threshold,severity,enabled,notification_enabled) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", id, request.name(), request.url(), request.method().name(), request.expectedStatus(), request.timeoutMs(), request.intervalSeconds(), request.failureThreshold(), request.recoveryThreshold(), request.severity().name(), request.enabled(), request.notificationEnabled());
        return response(id, request);
    }
    public List<MonitoredServiceResponse> list() {
        return jdbc.query("SELECT id,name,url,http_method,expected_status,timeout_ms,interval_seconds,failure_threshold,recovery_threshold,severity,enabled,notification_enabled FROM monitored_service ORDER BY name", (row, index) -> new MonitoredServiceResponse(row.getObject("id", UUID.class), row.getString("name"), row.getString("url"), row.getString("http_method"), row.getInt("expected_status"), row.getInt("timeout_ms"), row.getInt("interval_seconds"), row.getInt("failure_threshold"), row.getInt("recovery_threshold"), row.getString("severity"), row.getBoolean("enabled"), row.getBoolean("notification_enabled")));
    }
    private static MonitoredServiceResponse response(UUID id, MonitoredServiceRequest r) { return new MonitoredServiceResponse(id,r.name(),r.url(),r.method().name(),r.expectedStatus(),r.timeoutMs(),r.intervalSeconds(),r.failureThreshold(),r.recoveryThreshold(),r.severity().name(),r.enabled(),r.notificationEnabled()); }
}
