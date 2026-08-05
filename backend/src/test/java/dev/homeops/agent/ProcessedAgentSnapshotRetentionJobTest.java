package dev.homeops.agent;

import static org.mockito.Mockito.verify;

import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.agent.persistence.ProcessedAgentSnapshotStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcessedAgentSnapshotRetentionJobTest {

    @Mock
    private ProcessedAgentSnapshotStore store;

    @Test
    void should_deleteExpiredSnapshots_when_cleanupRuns() {
        Instant now = Instant.parse("2026-08-05T03:47:00Z");
        var properties = new HomeOpsAgentProperties(
                "local-mac",
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                Duration.ofMinutes(1),
                128,
                Duration.ofDays(1));
        var job = new ProcessedAgentSnapshotRetentionJob(
                store,
                properties,
                Clock.fixed(now, ZoneOffset.UTC));

        job.deleteExpiredSnapshots();

        verify(store).deleteProcessedBefore(now.minus(Duration.ofDays(1)));
    }
}
