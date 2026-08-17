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
import dev.homeops.agent.api.AgentStatusResponse.ConnectionStatus;
import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.agent.logs.ContainerLogCapabilityUnavailableException;
import dev.homeops.agent.logs.ContainerLogsNotAllowedException;
import dev.homeops.agent.persistence.AgentStatusEntity;
import dev.homeops.agent.persistence.AgentActivityStore;
import dev.homeops.agent.persistence.AgentStatusRepository;
import dev.homeops.agent.persistence.AgentStatusStore;
import dev.homeops.agent.persistence.HostMetricAggregateRepository;
import dev.homeops.agent.persistence.ProcessedAgentSnapshotStore;
import dev.homeops.common.AgentSnapshotRejectedException;
import dev.homeops.system.AmbiguousContainerIdentifierException;
import dev.homeops.system.ContainerInventoryUnavailableException;
import dev.homeops.system.ContainerNotFoundException;
import dev.homeops.system.InvalidContainerIdentifierException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentSnapshotServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final String FULL_CONTAINER_ID =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SHORT_CONTAINER_ID = "0123456789ab";

    @Mock
    private AgentStatusRepository agentStatusRepository;

    @Mock
    private AgentStatusStore agentStatusStore;

    @Mock
    private HostMetricAggregateRepository metricRepository;

    @Mock
    private ProcessedAgentSnapshotStore processedSnapshotStore;

    @Mock
    private AgentActivityStore agentActivityStore;

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
                agentStatusStore,
                metricRepository,
                processedSnapshotStore,
                agentActivityStore,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(processedSnapshotStore.recordIfAbsent(
                anyString(), any(), any(), any()))
                .thenReturn(true);
        lenient().when(agentStatusStore.insertIfAbsent(
                anyString(), any(), anyString(), any(), any()))
                .thenReturn(true);
    }

    @Test
    void should_acceptAndExposeSnapshot_when_requestIsValid() {
        UUID snapshotId = UUID.fromString(
                "10000000-0000-0000-0000-000000000001");
        AgentSnapshotRequest request = AgentSnapshotFixtures.snapshot(
                snapshotId,
                NOW.minusSeconds(2));
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
        verify(agentStatusStore).insertIfAbsent(
                "local-mac", snapshotId, request.agentVersion(), request.capturedAt(), NOW);
        verify(metricRepository).save(any());
        verify(agentActivityStore).recordConnection("local-mac", request.agentVersion(), NOW, false);
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
        verify(agentStatusStore, never()).insertIfAbsent(anyString(), any(), anyString(), any(), any());
        verify(agentActivityStore, never()).recordConnection(anyString(), anyString(), any(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void should_returnDuplicate_when_captureTimestampHasPostgresqlRoundingFraction() {
        UUID snapshotId = UUID.fromString("10000000-0000-0000-0000-000000000025");
        Instant rawCapturedAt = NOW.minusSeconds(1).plusNanos(789);
        Instant storedCapturedAt = NOW.minusSeconds(1).plusNanos(1_000);
        AgentSnapshotRequest request = AgentSnapshotFixtures.snapshot(snapshotId, rawCapturedAt);
        when(processedSnapshotStore.recordIfAbsent(anyString(), any(), any(), any())).thenReturn(false);
        when(processedSnapshotStore.findCapturedAt("local-mac", snapshotId))
                .thenReturn(Optional.of(storedCapturedAt));

        var accepted = service.accept(request);

        assertThat(accepted.duplicate()).isTrue();
        verify(processedSnapshotStore).recordIfAbsent("local-mac", snapshotId, storedCapturedAt, NOW);
        verify(metricRepository, never()).save(any());
    }

    @Test
    void should_notRecordActivity_when_agentVersionIsUnchanged() {
        UUID snapshotId = UUID.fromString("10000000-0000-0000-0000-000000000012");
        AgentSnapshotRequest request = AgentSnapshotFixtures.snapshot(snapshotId, NOW.minusSeconds(1));
        AgentStatusEntity status = AgentStatusEntity.create("local-mac");
        status.recordSnapshot(UUID.randomUUID(), request.agentVersion(), NOW.minusSeconds(6), NOW.minusSeconds(5));
        when(agentStatusStore.insertIfAbsent(anyString(), any(), anyString(), any(), any())).thenReturn(false);
        when(agentStatusStore.updateIfCapturedAtIsNotOlder(anyString(), any(), anyString(), any(), any()))
                .thenReturn(true);
        when(agentStatusRepository.findById("local-mac")).thenReturn(Optional.of(status));
        when(metricRepository.findByAgentIdAndBucketStart(any(), any())).thenReturn(Optional.empty());

        service.accept(request);

        verify(agentActivityStore, never()).recordConnection(anyString(), anyString(), any(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void should_updateCurrentStatusAndRecordVersionChange_when_snapshotIsNewer() {
        Instant previousCapturedAt = NOW.minusSeconds(3);
        Instant newerCapturedAt = NOW.minusSeconds(1);
        AgentSnapshotRequest request = snapshot(
                "10000000-0000-0000-0000-000000000026", newerCapturedAt, "v2");
        when(agentStatusStore.insertIfAbsent(anyString(), any(), anyString(), any(), any())).thenReturn(false);
        when(agentStatusStore.updateIfCapturedAtIsNotOlder(anyString(), any(), anyString(), any(), any()))
                .thenReturn(true);
        when(agentStatusRepository.findById("local-mac"))
                .thenReturn(Optional.of(status("v1", previousCapturedAt)));
        when(metricRepository.findByAgentIdAndBucketStart(any(), any())).thenReturn(Optional.empty());

        service.accept(request);

        assertThat(service.latest()).map(snapshot -> snapshot.snapshot().agentVersion()).contains("v2");
        verify(agentStatusStore).updateIfCapturedAtIsNotOlder(
                "local-mac", request.snapshotId(), "v2", newerCapturedAt, NOW);
        verify(agentActivityStore).recordConnection("local-mac", "v2", NOW, true);
    }

    @Test
    void should_keepCurrentVersionAndLatest_when_delayedOlderSnapshotReturnsToPreviousVersion() {
        Instant olderCapturedAt = NOW.minusSeconds(3);
        Instant newerCapturedAt = NOW.minusSeconds(1);
        AgentSnapshotRequest newer = snapshot(
                "10000000-0000-0000-0000-000000000027", newerCapturedAt, "v2");
        AgentSnapshotRequest delayed = snapshot(
                "10000000-0000-0000-0000-000000000028", olderCapturedAt, "v1");
        when(agentStatusStore.insertIfAbsent(anyString(), any(), anyString(), any(), any()))
                .thenReturn(false, false);
        when(agentStatusStore.updateIfCapturedAtIsNotOlder(anyString(), any(), anyString(), any(), any()))
                .thenReturn(true, false);
        when(agentStatusRepository.findById("local-mac"))
                .thenReturn(Optional.of(status("v1", olderCapturedAt)), Optional.of(status("v2", newerCapturedAt)));
        when(metricRepository.findByAgentIdAndBucketStart(any(), any())).thenReturn(Optional.empty());

        service.accept(newer);
        service.accept(delayed);

        assertThat(service.latest()).map(snapshot -> snapshot.snapshot().agentVersion()).contains("v2");
        assertThat(service.containerInventory().lastUpdatedAt()).isEqualTo(newerCapturedAt);
        verify(metricRepository, times(2)).save(any());
        verify(agentActivityStore, times(1)).recordConnection("local-mac", "v2", NOW, true);
        verify(agentActivityStore, never()).recordConnection("local-mac", "v1", NOW, true);
    }

    @Test
    void should_notRecordVersionChange_when_delayedOlderSnapshotHasDifferentNewVersion() {
        Instant olderCapturedAt = NOW.minusSeconds(3);
        Instant newerCapturedAt = NOW.minusSeconds(1);
        AgentSnapshotRequest newer = snapshot(
                "10000000-0000-0000-0000-000000000029", newerCapturedAt, "v2");
        AgentSnapshotRequest delayed = snapshot(
                "10000000-0000-0000-0000-000000000030", olderCapturedAt, "v3");
        when(agentStatusStore.insertIfAbsent(anyString(), any(), anyString(), any(), any()))
                .thenReturn(false, false);
        when(agentStatusStore.updateIfCapturedAtIsNotOlder(anyString(), any(), anyString(), any(), any()))
                .thenReturn(true, false);
        when(agentStatusRepository.findById("local-mac"))
                .thenReturn(Optional.of(status("v1", olderCapturedAt)), Optional.of(status("v2", newerCapturedAt)));
        when(metricRepository.findByAgentIdAndBucketStart(any(), any())).thenReturn(Optional.empty());

        service.accept(newer);
        service.accept(delayed);

        assertThat(service.latest()).map(snapshot -> snapshot.snapshot().agentVersion()).contains("v2");
        verify(metricRepository, times(2)).save(any());
        verify(agentActivityStore, times(1)).recordConnection("local-mac", "v2", NOW, true);
        verify(agentActivityStore, never()).recordConnection("local-mac", "v3", NOW, true);
    }

    @Test
    void should_notUpdateCurrentStatus_when_delayedOlderSnapshotHasSameVersion() {
        Instant olderCapturedAt = NOW.minusSeconds(3);
        Instant newerCapturedAt = NOW.minusSeconds(1);
        AgentSnapshotRequest newer = snapshot(
                "10000000-0000-0000-0000-000000000031", newerCapturedAt, "v2");
        AgentSnapshotRequest delayed = snapshot(
                "10000000-0000-0000-0000-000000000032", olderCapturedAt, "v2");
        when(agentStatusStore.insertIfAbsent(anyString(), any(), anyString(), any(), any()))
                .thenReturn(false, false);
        when(agentStatusStore.updateIfCapturedAtIsNotOlder(anyString(), any(), anyString(), any(), any()))
                .thenReturn(true, false);
        when(agentStatusRepository.findById("local-mac"))
                .thenReturn(Optional.of(status("v1", olderCapturedAt)), Optional.of(status("v2", newerCapturedAt)));
        when(metricRepository.findByAgentIdAndBucketStart(any(), any())).thenReturn(Optional.empty());

        service.accept(newer);
        service.accept(delayed);

        assertThat(service.latest()).map(snapshot -> snapshot.snapshot().capturedAt()).contains(newerCapturedAt);
        verify(metricRepository, times(2)).save(any());
        verify(agentActivityStore, times(1)).recordConnection("local-mac", "v2", NOW, true);
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
        AgentStatusEntity firstStatus = status("0.1.0", firstCapturedAt);
        when(agentStatusStore.insertIfAbsent(anyString(), any(), anyString(), any(), any()))
                .thenReturn(true, false);
        when(agentStatusStore.updateIfCapturedAtIsNotOlder(anyString(), any(), anyString(), any(), any()))
                .thenReturn(true);
        when(agentStatusRepository.findById("local-mac"))
                .thenReturn(Optional.of(firstStatus));
        when(metricRepository.findByAgentIdAndBucketStart(any(), any()))
                .thenReturn(Optional.empty());

        service.accept(first);
        service.accept(second);
        var retry = service.accept(first);

        assertThat(retry.duplicate()).isTrue();
        assertThat(service.containerInventory().lastUpdatedAt())
                .isEqualTo(secondCapturedAt);
        verify(metricRepository, times(2)).save(any());
        verify(agentStatusStore, times(2)).insertIfAbsent(anyString(), any(), anyString(), any(), any());
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
    void should_rejectContainerDetailAsUnavailable_when_latestSnapshotIsMissing() {
        assertThatThrownBy(() -> service.containerDetail(SHORT_CONTAINER_ID))
                .isInstanceOf(ContainerInventoryUnavailableException.class)
                .hasMessage("Container inventory is unavailable");
    }

    @Test
    void should_returnFreshContainerDetail_when_fullIdentifierHasSinglePrefixMatch() {
        Instant capturedAt = NOW.minusSeconds(1);
        accept(snapshotWithContainers(
                "10000000-0000-0000-0000-000000000040",
                capturedAt,
                List.of(container(FULL_CONTAINER_ID, "example-api", capturedAt))));

        var detail = service.containerDetail(SHORT_CONTAINER_ID);

        assertThat(detail.agentStatus()).isEqualTo("CONNECTED");
        assertThat(detail.lastUpdatedAt()).isEqualTo(capturedAt);
        assertThat(detail.stale()).isFalse();
        assertThat(detail.container().id()).isEqualTo(SHORT_CONTAINER_ID);
        assertThat(detail.container().id()).doesNotContain(FULL_CONTAINER_ID);
        assertThat(detail.container().name()).isEqualTo("example-api");
        assertThat(detail.supportsContainerLogs()).isFalse();
        assertThat(detail.container().logsAllowed()).isFalse();
    }

    @Test
    void should_authorizeContainerLogs_when_capabilityOptInAndFreshnessArePresent() {
        Instant capturedAt = NOW.minusSeconds(1);
        accept(snapshotWithCapability(
                "10000000-0000-0000-0000-000000000047",
                capturedAt,
                true,
                List.of(container(FULL_CONTAINER_ID, "example-api", capturedAt, true))));

        var eligibility = service.authorizeContainerLogs(SHORT_CONTAINER_ID);
        var detail = service.containerDetail(SHORT_CONTAINER_ID);

        assertThat(eligibility.containerId()).isEqualTo(SHORT_CONTAINER_ID);
        assertThat(detail.supportsContainerLogs()).isTrue();
        assertThat(detail.container().logsAllowed()).isTrue();
    }

    @Test
    void should_failClosedForContainerLogs_when_snapshotIsStale() {
        Instant capturedAt = NOW.minus(Duration.ofMinutes(4));
        accept(snapshotWithCapability(
                "10000000-0000-0000-0000-000000000048",
                capturedAt,
                true,
                List.of(container(FULL_CONTAINER_ID, "example-api", capturedAt, true))));

        assertThatThrownBy(() -> service.authorizeContainerLogs(SHORT_CONTAINER_ID))
                .isInstanceOf(ContainerLogCapabilityUnavailableException.class)
                .hasMessageNotContaining(FULL_CONTAINER_ID);
    }

    @Test
    void should_failClosedForContainerLogs_when_oldAgentOmitsCapability() {
        Instant capturedAt = NOW.minusSeconds(1);
        accept(snapshotWithCapability(
                "10000000-0000-0000-0000-000000000049",
                capturedAt,
                false,
                List.of(container(FULL_CONTAINER_ID, "example-api", capturedAt, true))));

        assertThat(service.containerInventory().containers()).hasSize(1);
        assertThat(service.containerDetail(SHORT_CONTAINER_ID).container().name())
                .isEqualTo("example-api");
        assertThatThrownBy(() -> service.authorizeContainerLogs(SHORT_CONTAINER_ID))
                .isInstanceOf(ContainerLogCapabilityUnavailableException.class);
    }

    @Test
    void should_revokeContainerLogCapability_when_oldAgentSnapshotReturnsAfterRollback() {
        Instant firstCapturedAt = NOW.minusSeconds(2);
        Instant rollbackCapturedAt = NOW.minusSeconds(1);
        accept(snapshotWithCapability(
                "10000000-0000-0000-0000-000000000050",
                firstCapturedAt,
                true,
                List.of(container(FULL_CONTAINER_ID, "example-api", firstCapturedAt, true))));
        assertThat(service.authorizeContainerLogs(SHORT_CONTAINER_ID).containerId())
                .isEqualTo(SHORT_CONTAINER_ID);

        accept(snapshotWithCapability(
                "10000000-0000-0000-0000-000000000051",
                rollbackCapturedAt,
                false,
                List.of(container(FULL_CONTAINER_ID, "example-api", rollbackCapturedAt, false))));

        assertThat(service.summary().agentStatus()).isEqualTo("CONNECTED");
        assertThat(service.containerInventory().containers()).hasSize(1);
        assertThat(service.containerDetail(SHORT_CONTAINER_ID).supportsContainerLogs()).isFalse();
        assertThatThrownBy(() -> service.authorizeContainerLogs(SHORT_CONTAINER_ID))
                .isInstanceOf(ContainerLogCapabilityUnavailableException.class);
    }

    @Test
    void should_rejectContainerLogs_when_containerHasNotOptedIn() {
        Instant capturedAt = NOW.minusSeconds(1);
        accept(snapshotWithCapability(
                "10000000-0000-0000-0000-000000000052",
                capturedAt,
                true,
                List.of(container(FULL_CONTAINER_ID, "example-api", capturedAt, false))));

        assertThatThrownBy(() -> service.authorizeContainerLogs(SHORT_CONTAINER_ID))
                .isInstanceOf(ContainerLogsNotAllowedException.class)
                .hasMessageNotContaining(FULL_CONTAINER_ID);
    }

    @Test
    void should_returnStaleContainerDetail_when_singleMatchComesFromStaleSnapshot() {
        Instant capturedAt = NOW.minus(Duration.ofMinutes(4));
        accept(snapshotWithContainers(
                "10000000-0000-0000-0000-000000000041",
                capturedAt,
                List.of(container(FULL_CONTAINER_ID, "example-api", capturedAt))));

        var detail = service.containerDetail(SHORT_CONTAINER_ID);

        assertThat(detail.agentStatus()).isEqualTo("STALE");
        assertThat(detail.lastUpdatedAt()).isEqualTo(capturedAt);
        assertThat(detail.stale()).isTrue();
        assertThat(detail.container().state()).isEqualTo("RUNNING");
    }

    @Test
    void should_rejectContainerDetailAsNotFound_when_prefixHasNoMatch() {
        Instant capturedAt = NOW.minusSeconds(1);
        accept(snapshotWithContainers(
                "10000000-0000-0000-0000-000000000042",
                capturedAt,
                List.of(container(FULL_CONTAINER_ID, "example-api", capturedAt))));

        assertThatThrownBy(() -> service.containerDetail("ffffffffffff"))
                .isInstanceOf(ContainerNotFoundException.class)
                .hasMessage("Container is not present in the latest reported snapshot")
                .hasMessageNotContaining(FULL_CONTAINER_ID);
    }

    @Test
    void should_rejectContainerDetailAsAmbiguous_when_fullIdentifiersShareShortPrefix() {
        String first = "aaaaaaaaaaaa11111111111111111111";
        String second = "aaaaaaaaaaaa22222222222222222222";
        Instant capturedAt = NOW.minusSeconds(1);
        accept(snapshotWithContainers(
                "10000000-0000-0000-0000-000000000043",
                capturedAt,
                List.of(
                        container(first, "first-api", capturedAt),
                        container(second, "second-api", capturedAt))));

        assertThatThrownBy(() -> service.containerDetail("aaaaaaaaaaaa"))
                .isInstanceOf(AmbiguousContainerIdentifierException.class)
                .hasMessage("Container identifier matches multiple reported containers")
                .hasMessageNotContaining(first)
                .hasMessageNotContaining(second);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0123456789a",
        "0123456789abc",
        "0123456789AB",
        "0123456789ag",
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    })
    void should_rejectContainerDetail_when_identifierSyntaxIsOutsideBound(String identifier) {
        Instant capturedAt = NOW.minusSeconds(1);
        accept(snapshotWithContainers(
                "10000000-0000-0000-0000-000000000044",
                capturedAt,
                List.of(container(FULL_CONTAINER_ID, "example-api", capturedAt))));

        assertThatThrownBy(() -> service.containerDetail(identifier))
                .isInstanceOf(InvalidContainerIdentifierException.class)
                .hasMessage("Container identifier is invalid")
                .hasMessageNotContaining(identifier);
    }

    @Test
    void should_useChronologicallyNewestSnapshot_when_olderSnapshotArrivesLater() {
        Instant olderCapturedAt = NOW.minusSeconds(3);
        Instant newerCapturedAt = NOW.minusSeconds(1);
        String oldIdentifier = "11111111111111111111111111111111";
        String newIdentifier = "22222222222222222222222222222222";
        when(metricRepository.findByAgentIdAndBucketStart(any(), any()))
                .thenReturn(Optional.empty());

        service.accept(snapshotWithContainers(
                "10000000-0000-0000-0000-000000000045",
                newerCapturedAt,
                List.of(container(newIdentifier, "new-api", newerCapturedAt))));
        service.accept(snapshotWithContainers(
                "10000000-0000-0000-0000-000000000046",
                olderCapturedAt,
                List.of(container(oldIdentifier, "old-api", olderCapturedAt))));

        assertThat(service.containerDetail("222222222222").container().name())
                .isEqualTo("new-api");
        assertThatThrownBy(() -> service.containerDetail("111111111111"))
                .isInstanceOf(ContainerNotFoundException.class);
    }

    @Test
    void should_markDelayedSnapshotStale_when_captureTimeExceedsFreshnessWindow() {
        AgentSnapshotRequest request = AgentSnapshotFixtures.snapshot(
                UUID.fromString("10000000-0000-0000-0000-000000000008"),
                NOW.minus(Duration.ofMinutes(4)));
        when(metricRepository.findByAgentIdAndBucketStart(any(), any()))
                .thenReturn(Optional.empty());

        service.accept(request);

        var status = service.status();
        var summary = service.summary();
        var inventory = service.containerInventory();

        assertThat(status.status()).isEqualTo(ConnectionStatus.STALE);
        assertThat(status.stale()).isTrue();
        assertThat(summary.agentStatus()).isEqualTo("STALE");
        assertThat(summary.stale()).isTrue();
        assertThat(inventory.agentStatus()).isEqualTo("STALE");
        assertThat(inventory.stale()).isTrue();
    }

    @Test
    void should_rejectSnapshot_when_snapshotIsTooOld() {
        AgentSnapshotRequest request = AgentSnapshotFixtures.snapshot(
                UUID.fromString("10000000-0000-0000-0000-000000000003"),
                NOW.minus(Duration.ofMinutes(6)));

        assertThatThrownBy(() -> service.accept(request))
                .isInstanceOf(AgentSnapshotRejectedException.class)
                .hasMessage("Snapshot is older than the configured limit");
        verify(agentStatusStore, never()).insertIfAbsent(anyString(), any(), anyString(), any(), any());
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

    private static AgentStatusEntity status(String version, Instant capturedAt) {
        AgentStatusEntity status = AgentStatusEntity.create("local-mac");
        status.recordSnapshot(UUID.randomUUID(), version, capturedAt, capturedAt.plusSeconds(1));
        return status;
    }

    private static AgentSnapshotRequest snapshot(String snapshotId, Instant capturedAt, String version) {
        AgentSnapshotRequest fixture = AgentSnapshotFixtures.snapshot(UUID.fromString(snapshotId), capturedAt);
        return new AgentSnapshotRequest(
                fixture.snapshotId(), fixture.agentId(), version, fixture.capturedAt(),
                fixture.host(), fixture.containers());
    }

    private void accept(AgentSnapshotRequest request) {
        when(metricRepository.findByAgentIdAndBucketStart(any(), any()))
                .thenReturn(Optional.empty());
        service.accept(request);
    }

    private static AgentSnapshotRequest snapshotWithContainers(
            String snapshotId,
            Instant capturedAt,
            List<AgentSnapshotRequest.ContainerSnapshot> containers) {
        AgentSnapshotRequest fixture = AgentSnapshotFixtures.snapshot(
                UUID.fromString(snapshotId),
                capturedAt);
        return new AgentSnapshotRequest(
                fixture.snapshotId(),
                fixture.agentId(),
                fixture.agentVersion(),
                fixture.capturedAt(),
                fixture.host(),
                containers);
    }

    private static AgentSnapshotRequest snapshotWithCapability(
            String snapshotId,
            Instant capturedAt,
            boolean supportsContainerLogs,
            List<AgentSnapshotRequest.ContainerSnapshot> containers) {
        AgentSnapshotRequest fixture = AgentSnapshotFixtures.snapshot(
                UUID.fromString(snapshotId),
                capturedAt);
        return new AgentSnapshotRequest(
                fixture.snapshotId(),
                fixture.agentId(),
                fixture.agentVersion(),
                fixture.capturedAt(),
                supportsContainerLogs,
                fixture.host(),
                containers);
    }

    private static AgentSnapshotRequest.ContainerSnapshot container(
            String identifier,
            String name,
            Instant capturedAt) {
        return container(identifier, name, capturedAt, false);
    }

    private static AgentSnapshotRequest.ContainerSnapshot container(
            String identifier,
            String name,
            Instant capturedAt,
            boolean logsAllowed) {
        AgentSnapshotRequest.ContainerSnapshot fixture = AgentSnapshotFixtures
                .snapshot(UUID.fromString("10000000-0000-0000-0000-000000000099"), capturedAt)
                .containers()
                .getFirst();
        return new AgentSnapshotRequest.ContainerSnapshot(
                identifier,
                name,
                fixture.composeProject(),
                fixture.image(),
                fixture.state(),
                fixture.health(),
                fixture.status(),
                fixture.startedAt(),
                fixture.restartCount(),
                fixture.cpuUsagePercent(),
                fixture.memoryUsageBytes(),
                fixture.memoryLimitBytes(),
                fixture.ports(),
                fixture.managed(),
                logsAllowed);
    }
}
