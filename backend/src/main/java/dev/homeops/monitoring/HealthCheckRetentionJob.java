package dev.homeops.monitoring;

import dev.homeops.monitoring.config.HomeOpsMonitoringProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class HealthCheckRetentionJob {
    private final MonitoredServiceStore store;
    private final HomeOpsMonitoringProperties properties;
    private final Clock clock;

    @Autowired
    public HealthCheckRetentionJob(MonitoredServiceStore store, HomeOpsMonitoringProperties properties) {
        this(store, properties, Clock.systemUTC());
    }

    HealthCheckRetentionJob(MonitoredServiceStore store, HomeOpsMonitoringProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    @Scheduled(cron = "${homeops.monitoring.cleanup-cron}", zone = "UTC")
    public void removeExpiredResults() {
        Instant now = clock.instant();
        store.deleteExpiredResults(
                now.minus(properties.healthyResultRetention()),
                now.minus(properties.failureResultRetention()));
    }
}
