package dev.homeops.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.homeops.agent.api.AgentSnapshotRequest;
import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.agent.persistence.AgentStatusEntity;
import dev.homeops.agent.persistence.AgentStatusRepository;
import dev.homeops.agent.persistence.HostMetricAggregateRepository;
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

    private AgentSnapshotService service;

    @BeforeEach
    void setUp() {
        var properties = new HomeOpsAgentProperties(
                "local-mac",
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                Duration.ofMinutes(1),
                128);
        service = new AgentSnapshotService(
                properties,
                agentStatusRepository,
                metricRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
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
        assertThat(service.containers()).hasSize(1);
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
        AgentStatusEntity existing = AgentStatusEntity.create("local-mac");
        existing.recordSnapshot(
                snapshotId,
                "0.1.0",
                NOW.minusSeconds(1),
                NOW.minusSeconds(1));
        when(agentStatusRepository.findById("local-mac"))
                .thenReturn(Optional.of(existing));

        var accepted = service.accept(request);

        assertThat(accepted.duplicate()).isTrue();
        verify(metricRepository, never()).save(any());
        verify(agentStatusRepository, never()).save(any());
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
        AgentStatusEntity existing = AgentStatusEntity.create("local-mac");
        existing.recordSnapshot(
                snapshotId,
                "0.1.0",
                NOW.minusSeconds(2),
                NOW.minusSeconds(1));
        when(agentStatusRepository.findById("local-mac"))
                .thenReturn(Optional.of(existing));
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
