package dev.homeops.agent;

import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.agent.persistence.ProcessedAgentSnapshotStore;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProcessedAgentSnapshotRetentionJob {

    private final ProcessedAgentSnapshotStore store;
    private final HomeOpsAgentProperties properties;
    private final Clock clock;

    @Autowired
    public ProcessedAgentSnapshotRetentionJob(
            ProcessedAgentSnapshotStore store,
            HomeOpsAgentProperties properties) {
        this(store, properties, Clock.systemUTC());
    }

    ProcessedAgentSnapshotRetentionJob(
            ProcessedAgentSnapshotStore store,
            HomeOpsAgentProperties properties,
            Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${homeops.agent.processed-snapshot-cleanup-cron:0 47 3 * * *}",
            zone = "UTC")
    @Transactional
    public void deleteExpiredSnapshots() {
        Instant cutoff = clock.instant()
                .minus(properties.processedSnapshotRetention());
        store.deleteProcessedBefore(cutoff);
    }
}
