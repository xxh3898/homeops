package dev.homeops.metrics;

import dev.homeops.agent.persistence.HostMetricAggregateRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class HostMetricRetentionJob {

    private final HostMetricAggregateRepository repository;
    private final HomeOpsMetricProperties properties;
    private final Clock clock;

    @Autowired
    public HostMetricRetentionJob(
            HostMetricAggregateRepository repository,
            HomeOpsMetricProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    HostMetricRetentionJob(
            HostMetricAggregateRepository repository,
            HomeOpsMetricProperties properties,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${homeops.metrics.cleanup-cron:0 17 3 * * *}",
            zone = "UTC")
    @Transactional
    public void deleteExpiredAggregates() {
        Instant cutoff = clock.instant().minus(properties.retention());
        repository.deleteByBucketStartBefore(cutoff);
    }
}
