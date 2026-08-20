package dev.homeops.agent;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.agent.persistence.AgentStatusStore;
import dev.homeops.agent.persistence.AgentStatusStore.AgentStatusSnapshot;
import dev.homeops.notification.AgentLifecycleNotificationProducer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentFreshnessNotificationMonitorTest {
    private static final String AGENT_ID = "local-mac";
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000043");
    private static final Instant LAST_CAPTURED = Instant.parse("2026-08-20T02:00:00Z");

    @Mock private AgentStatusStore statusStore;
    @Mock private AgentLifecycleNotificationProducer producer;
    private HomeOpsAgentProperties properties;

    @BeforeEach
    void createProperties() {
        properties = new HomeOpsAgentProperties(
                AGENT_ID,
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                Duration.ofMinutes(1),
                128,
                Duration.ofDays(1));
    }

    @Test
    void should_notCreateOfflineIntent_when_expectedAgentHasNeverReported() {
        when(statusStore.findForUpdate(AGENT_ID)).thenReturn(Optional.empty());

        monitorAt(LAST_CAPTURED.plusSeconds(31)).checkFreshness();

        verify(producer, never()).recordStale(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void should_notCreateStaleRoot_when_thresholdHasNotElapsed() {
        when(statusStore.findForUpdate(AGENT_ID)).thenReturn(Optional.of(status()));

        monitorAt(LAST_CAPTURED.plusSeconds(30).minusNanos(1)).checkFreshness();

        verify(producer, never()).recordStale(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void should_notCreateStaleRoot_when_timestampIsExactlyAtThreshold() {
        when(statusStore.findForUpdate(AGENT_ID)).thenReturn(Optional.of(status()));

        monitorAt(LAST_CAPTURED.plusSeconds(30)).checkFreshness();

        verify(producer, never()).recordStale(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void should_createStaleRootFromPersistedSnapshot_when_thresholdHasElapsed() {
        Instant observedAt = LAST_CAPTURED.plusSeconds(30).plusNanos(1);
        when(statusStore.findForUpdate(AGENT_ID)).thenReturn(Optional.of(status()));

        monitorAt(observedAt).checkFreshness();

        verify(producer).recordStale(
                SNAPSHOT_ID,
                AGENT_ID,
                "v1",
                LAST_CAPTURED,
                observedAt);
    }

    private AgentFreshnessNotificationMonitor monitorAt(Instant now) {
        return new AgentFreshnessNotificationMonitor(
                properties,
                statusStore,
                producer,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static AgentStatusSnapshot status() {
        return new AgentStatusSnapshot(
                AGENT_ID,
                "v1",
                SNAPSHOT_ID,
                LAST_CAPTURED,
                LAST_CAPTURED.plusSeconds(1));
    }
}
