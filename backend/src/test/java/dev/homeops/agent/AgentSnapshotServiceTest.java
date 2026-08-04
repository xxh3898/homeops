package dev.homeops.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.homeops.agent.api.AgentSnapshotRequest;
import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.agent.persistence.AgentStatusEntity;
import dev.homeops.agent.persistence.AgentStatusRepository;
import dev.homeops.agent.persistence.HostMetricAggregateRepository;
import dev.homeops.agent.persistence.ProcessedAgentSnapshotStore;
import dev.homeops.common.AgentSnapshotRejectedException;
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
class AgentSnapshotServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");

    @Mock
    private AgentStatusRepository agentStatusRepository;

    @Mock
    private HostMetricAggregateRepository metricRepository;

    @Mock
    private ProcessedAgentSnapshotStore processedSnapshotStore;

    private AgentSnapshotService service;

    @BeforeEach
    void setUp() {
        var properties = new HomeOpsAgentProperties(
                "local-mac",
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                Duration.ofMinutes(1),
                128,
                Duration.ofDays(1));
        service = new AgentSnapshotService(
                properties,
                agentStatusRepository,
                metricRepository,
                processedSnapshotStore,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(processedSnapshotStore.recordIfAbsent(
                anyString(), any(), any(), any()))
                .thenReturn(true);
    }

    @Test
    void should_acceptAndExposeSnapshot_when_requestIsValid() {
        UUID snapshotId = UUID.fromString(
                "10000000-0000-0000-0000-000000000001");
        AgentSnapshotRequest request = AgentSnapshotFixtures.snapshot(
                snapshotId,
                NOW.minusSeconds(2));
        when(agentStatusRepository.findById("local-mac"))
                .thenReturn(Optional.empty());
        when(metricRepository.findByAgentIdAndBucketStart(any(), any()))
                .thenReturn(Optional.empty());

        var accepted = service.accept(request);
        var summary = service.summary();

        assertThat(accepted.duplicate()).isFalse();
        assertThat(accepted.snapshotId()).isEqualTo(snapshotId);
        assertThat(summary.stale()).isFalse();
        assertThat(summary.docker().total()).isEqualTo(1);
        assertThat(summary.docker().running()).isEqualTo(1);
        assertThat(service.containerInventory().containers()).hasSize(1);
        verify(agentStatusRepository).save(any(AgentStatusEntity.class));
        verify(metricRepository).save(any());
    }

    @Test
    void should_returnDuplicateWithoutNewMetric_when_snapshotWasProcessed() {
        UUID snapshotId = UUID.fromString(
                "10000000-0000-0000-0000-000000000002");
        AgentSnapshotRequest request = AgentSnapshotFixtures.snapshot(
                snapshotId,
                NOW.minusSeconds(1));
        when(processedSnapshotStore.recordIfAbsent(
                anyString(), any(), any(), any()))
                .thenReturn(false);
        when(processedSnapshotStore.findCapturedAt("local-mac", snapshotId))
                .thenReturn(Optional.of(NOW.minusSeconds(1)));

        var accepted = service.accept(request);

        assertThat(accepted.duplicate()).isTrue();
        verify(metricRepository, never()).save(any());
        verify(agentStatusRepository, never()).save(any());
    }

    @Test
    void should_notAggregateSnapshotAgain_when_retryFollowsNewerSnapshot() {
        UUID firstId = UUID.fromString(
                "10000000-0000-0000-0000-000000000006");
        UUID secondId = UUID.fromString(
                "10000000-0000-0000-0000-000000000007");
        Instant firstCapturedAt = NOW.minusSeconds(2);
        Instant secondCapturedAt = NOW.minusSeconds(1);
        AgentSnapshotRequest first = AgentSnapshotFixtures.snapshot(
                firstId,
                firstCapturedAt);
        AgentSnapshotRequest second = AgentSnapshotFixtures.snapshot(
                secondId,
                secondCapturedAt);
        when(processedSnapshotStore.recordIfAbsent(
                anyString(), any(), any(), any()))
                .thenReturn(true, true, false);
        when(processedSnapshotStore.findCapturedAt("local-mac", firstId))
                .thenReturn(Optional.of(firstCapturedAt));
        when(agentStatusRepository.findById("local-mac"))
                .thenReturn(Optional.empty());
        when(metricRepository.findByAgentIdAndBucketStart(any(), any()))
                .thenReturn(Optional.empty());

        service.accept(first);
        service.accept(second);
        var retry = service.accept(first);

        assertThat(retry.duplicate()).isTrue();
        assertThat(service.containerInventory().lastUpdatedAt())
                .isEqualTo(secondCapturedAt);
        verify(metricRepository, times(2)).save(any());
        verify(agentStatusRepository, times(2)).save(any());
    }

    @Test
    void should_returnStaleOfflineInventory_when_snapshotIsUnavailable() {
        var inventory = service.containerInventory();

        assertThat(inventory.agentStatus()).isEqualTo("OFFLINE");
        assertThat(inventory.lastUpdatedAt()).isNull();
        assertThat(inventory.stale()).isTrue();
        assertThat(inventory.containers()).isEmpty();
    }

    @Test
    void should_rejectSnapshot_when_snapshotIsTooOld() {
        AgentSnapshotRequest request = AgentSnapshotFixtures.snapshot(
                UUID.fromString("10000000-0000-0000-0000-000000000003"),
                NOW.minus(Duration.ofMinutes(6)));

        assertThatThrownBy(() -> service.accept(request))
                .isInstanceOf(AgentSnapshotRejectedException.class)
                .hasMessage("Snapshot is older than the configured limit");
        verify(agentStatusRepository, never()).save(any());
        verify(metricRepository, never()).save(any());
    }

    @Test
    void should_rejectSnapshot_when_agentIdentifierIsNotAllowed() {
        AgentSnapshotRequest valid = AgentSnapshotFixtures.snapshot(
                UUID.fromString("10000000-0000-0000-0000-000000000004"),
                NOW);
        AgentSnapshotRequest request = new AgentSnapshotRequest(
                valid.snapshotId(),
                "other-mac",
                valid.agentVersion(),
                valid.capturedAt(),
                valid.host(),
                valid.containers());

        assertThatThrownBy(() -> service.accept(request))
                .isInstanceOf(AgentSnapshotRejectedException.class)
                .hasMessage("Agent identifier is not allowed");
    }

    @Test
    void should_rejectSnapshot_when_idempotencyIdentifierHasDifferentTimestamp() {
        UUID snapshotId = UUID.fromString(
                "10000000-0000-0000-0000-000000000005");
        when(processedSnapshotStore.recordIfAbsent(
                anyString(), any(), any(), any()))
                .thenReturn(false);
        when(processedSnapshotStore.findCapturedAt("local-mac", snapshotId))
                .thenReturn(Optional.of(NOW.minusSeconds(2)));
        AgentSnapshotRequest request = AgentSnapshotFixtures.snapshot(
                snapshotId,
                NOW.minusSeconds(1));

        assertThatThrownBy(() -> service.accept(request))
                .isInstanceOf(AgentSnapshotRejectedException.class)
                .hasMessage(
                        "Snapshot identifier conflicts with a prior capture time");
        verify(metricRepository, never()).save(any());
    }
}
