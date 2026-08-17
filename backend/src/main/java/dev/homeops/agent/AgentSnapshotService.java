package dev.homeops.agent;

import dev.homeops.agent.api.AgentSnapshotAcceptedResponse;
import dev.homeops.agent.api.AgentSnapshotRequest;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerHealth;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerState;
import dev.homeops.agent.api.AgentStatusResponse;
import dev.homeops.agent.api.AgentStatusResponse.ConnectionStatus;
import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.agent.domain.ReceivedAgentSnapshot;
import dev.homeops.agent.logs.ContainerLogCapabilityUnavailableException;
import dev.homeops.agent.logs.ContainerLogEligibility;
import dev.homeops.agent.logs.ContainerLogsNotAllowedException;
import dev.homeops.agent.persistence.AgentActivityStore;
import dev.homeops.agent.persistence.AgentStatusEntity;
import dev.homeops.agent.persistence.AgentStatusRepository;
import dev.homeops.agent.persistence.AgentStatusStore;
import dev.homeops.agent.persistence.HostMetricAggregateEntity;
import dev.homeops.agent.persistence.HostMetricAggregateRepository;
import dev.homeops.agent.persistence.ProcessedAgentSnapshotStore;
import dev.homeops.common.AgentSnapshotRejectedException;
import dev.homeops.common.PostgresqlTimestamp;
import dev.homeops.system.AmbiguousContainerIdentifierException;
import dev.homeops.system.ContainerIdentifier;
import dev.homeops.system.ContainerInventoryUnavailableException;
import dev.homeops.system.ContainerNotFoundException;
import dev.homeops.system.api.ContainerDetailResponse;
import dev.homeops.system.api.ContainerInventoryResponse;
import dev.homeops.system.api.ContainerView;
import dev.homeops.system.api.SystemSummaryResponse;
import dev.homeops.system.api.SystemSummaryResponse.DockerSummaryView;
import dev.homeops.system.api.SystemSummaryResponse.HostMetricView;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AgentSnapshotService {

    private final HomeOpsAgentProperties properties;
    private final AgentStatusRepository agentStatusRepository;
    private final AgentStatusStore agentStatusStore;
    private final HostMetricAggregateRepository metricRepository;
    private final ProcessedAgentSnapshotStore processedSnapshotStore;
    private final AgentActivityStore agentActivityStore;
    private final Clock clock;
    private final AtomicReference<ReceivedAgentSnapshot> latest =
            new AtomicReference<>();

    @Autowired
    public AgentSnapshotService(
            HomeOpsAgentProperties properties,
            AgentStatusRepository agentStatusRepository,
            AgentStatusStore agentStatusStore,
            HostMetricAggregateRepository metricRepository,
            ProcessedAgentSnapshotStore processedSnapshotStore,
            AgentActivityStore agentActivityStore) {
        this(
                properties,
                agentStatusRepository,
                agentStatusStore,
                metricRepository,
                processedSnapshotStore,
                agentActivityStore,
                Clock.systemUTC());
    }

    AgentSnapshotService(
            HomeOpsAgentProperties properties,
            AgentStatusRepository agentStatusRepository,
            AgentStatusStore agentStatusStore,
            HostMetricAggregateRepository metricRepository,
            ProcessedAgentSnapshotStore processedSnapshotStore,
            AgentActivityStore agentActivityStore,
            Clock clock) {
        this.properties = properties;
        this.agentStatusRepository = agentStatusRepository;
        this.agentStatusStore = agentStatusStore;
        this.metricRepository = metricRepository;
        this.processedSnapshotStore = processedSnapshotStore;
        this.agentActivityStore = agentActivityStore;
        this.clock = clock;
    }

    @Transactional
    public AgentSnapshotAcceptedResponse accept(AgentSnapshotRequest request) {
        Instant receivedAt = clock.instant();
        validate(request, receivedAt);
        AgentSnapshotRequest canonicalRequest = canonicalize(request);

        boolean firstProcessing = processedSnapshotStore.recordIfAbsent(
                canonicalRequest.agentId(),
                canonicalRequest.snapshotId(),
                canonicalRequest.capturedAt(),
                receivedAt);
        if (!firstProcessing) {
            Instant processedCapturedAt = processedSnapshotStore
                    .findCapturedAt(canonicalRequest.agentId(), canonicalRequest.snapshotId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Processed snapshot claim is missing"));
            if (!sameDatabaseTimestamp(
                    canonicalRequest.capturedAt(),
                    processedCapturedAt)) {
                throw new AgentSnapshotRejectedException(
                        "Snapshot identifier conflicts with a prior capture time");
            }
            publishLatestAfterCommit(new ReceivedAgentSnapshot(canonicalRequest, receivedAt));
            return new AgentSnapshotAcceptedResponse(
                    canonicalRequest.snapshotId(),
                    receivedAt,
                    true);
        }

        boolean firstConnection = agentStatusStore.insertIfAbsent(
                canonicalRequest.agentId(),
                canonicalRequest.snapshotId(),
                canonicalRequest.agentVersion(),
                canonicalRequest.capturedAt(),
                receivedAt);
        Optional<AgentStatusEntity> persistedStatus = firstConnection
                ? Optional.empty()
                : agentStatusRepository.findById(canonicalRequest.agentId());
        boolean versionChanged = persistedStatus
                .map(existing -> !existing.getAgentVersion().equals(canonicalRequest.agentVersion()))
                .orElse(false);
        Instant bucket = canonicalRequest.capturedAt().truncatedTo(ChronoUnit.MINUTES);
        Optional<HostMetricAggregateEntity> existingAggregate = metricRepository
                .findByAgentIdAndBucketStart(canonicalRequest.agentId(), bucket);
        HostMetricAggregateEntity aggregate;
        if (existingAggregate.isPresent()) {
            aggregate = existingAggregate.get();
            aggregate.addSample(canonicalRequest.host());
        } else {
            aggregate = HostMetricAggregateEntity.create(
                    canonicalRequest.agentId(),
                    bucket,
                    canonicalRequest.host());
        }
        metricRepository.save(aggregate);

        boolean currentStatusUpdated = firstConnection || agentStatusStore.updateIfCapturedAtIsNotOlder(
                canonicalRequest.agentId(),
                canonicalRequest.snapshotId(),
                canonicalRequest.agentVersion(),
                canonicalRequest.capturedAt(),
                receivedAt);
        if (currentStatusUpdated && (firstConnection || versionChanged)) {
            agentActivityStore.recordConnection(
                    canonicalRequest.agentId(), canonicalRequest.agentVersion(), receivedAt, versionChanged);
        }
        publishLatestAfterCommit(new ReceivedAgentSnapshot(canonicalRequest, receivedAt));

        return new AgentSnapshotAcceptedResponse(
                canonicalRequest.snapshotId(),
                receivedAt,
                false);
    }

    public Optional<ReceivedAgentSnapshot> latest() {
        return Optional.ofNullable(latest.get());
    }

    @Transactional(readOnly = true)
    public AgentStatusResponse status() {
        Instant now = clock.instant();
        return latest()
                .map(snapshot -> toConnectedStatus(snapshot, now))
                .orElseGet(() -> agentStatusRepository
                        .findById(properties.expectedId())
                        .map(entity -> toPersistedStatus(entity, now))
                        .orElse(new AgentStatusResponse(
                                properties.expectedId(),
                                null,
                                ConnectionStatus.OFFLINE,
                                null,
                                null,
                                true)));
    }

    public SystemSummaryResponse summary() {
        return latest()
                .map(this::toSummary)
                .orElse(new SystemSummaryResponse(
                        ConnectionStatus.OFFLINE.name(),
                        null,
                        true,
                        null,
                        new DockerSummaryView(0, 0, 0, 0)));
    }

    public ContainerInventoryResponse containerInventory() {
        return latest()
                .map(this::toContainerInventory)
                .orElse(new ContainerInventoryResponse(
                        ConnectionStatus.OFFLINE.name(),
                        null,
                        true,
                        List.of()));
    }

    public ContainerDetailResponse containerDetail(String rawIdentifier) {
        ReceivedAgentSnapshot received = latest()
                .orElseThrow(ContainerInventoryUnavailableException::new);
        ContainerIdentifier identifier = ContainerIdentifier.parse(rawIdentifier);
        List<AgentSnapshotRequest.ContainerSnapshot> matches = received.snapshot()
                .containers()
                .stream()
                .filter(container -> identifier.matches(container.id()))
                .limit(2)
                .toList();
        if (matches.isEmpty()) {
            throw new ContainerNotFoundException();
        }
        if (matches.size() > 1) {
            throw new AmbiguousContainerIdentifierException();
        }
        boolean stale = isSnapshotStale(received);
        return new ContainerDetailResponse(
                stale
                        ? ConnectionStatus.STALE.name()
                        : ConnectionStatus.CONNECTED.name(),
                received.snapshot().capturedAt(),
                stale,
                received.snapshot().supportsContainerLogs(),
                ContainerView.from(matches.getFirst()));
    }

    public ContainerLogEligibility authorizeContainerLogs(String rawIdentifier) {
        ReceivedAgentSnapshot received = latest()
                .orElseThrow(ContainerInventoryUnavailableException::new);
        ContainerIdentifier identifier = ContainerIdentifier.parse(rawIdentifier);
        if (isSnapshotStale(received)
                || !received.snapshot().supportsContainerLogs()) {
            throw new ContainerLogCapabilityUnavailableException();
        }
        List<AgentSnapshotRequest.ContainerSnapshot> matches = received.snapshot()
                .containers()
                .stream()
                .filter(container -> identifier.matches(container.id()))
                .limit(2)
                .toList();
        if (matches.isEmpty()) {
            throw new ContainerNotFoundException();
        }
        if (matches.size() > 1) {
            throw new AmbiguousContainerIdentifierException();
        }
        if (!matches.getFirst().logsAllowed()) {
            throw new ContainerLogsNotAllowedException();
        }
        return new ContainerLogEligibility(identifier.value());
    }

    private void validate(AgentSnapshotRequest request, Instant receivedAt) {
        if (!properties.expectedId().equals(request.agentId())) {
            throw new AgentSnapshotRejectedException(
                    "Agent identifier is not allowed");
        }
        if (request.containers().size() > properties.maximumContainers()) {
            throw new AgentSnapshotRejectedException(
                    "Container count exceeds the configured limit");
        }
        if (request.capturedAt().isBefore(
                receivedAt.minus(properties.maximumSnapshotAge()))) {
            throw new AgentSnapshotRejectedException(
                    "Snapshot is older than the configured limit");
        }
        if (request.capturedAt().isAfter(
                receivedAt.plus(properties.allowedFutureSkew()))) {
            throw new AgentSnapshotRejectedException(
                    "Snapshot timestamp is too far in the future");
        }
        if (request.host().memoryUsedBytes()
                > request.host().memoryTotalBytes()) {
            throw new AgentSnapshotRejectedException(
                    "Used memory cannot exceed total memory");
        }
        if (request.host().diskUsedBytes()
                > request.host().diskTotalBytes()) {
            throw new AgentSnapshotRejectedException(
                    "Used disk cannot exceed total disk");
        }
        boolean invalidContainerMemory = request.containers().stream()
                .anyMatch(container -> container.memoryUsageBytes() != null
                        && container.memoryLimitBytes() != null
                        && container.memoryUsageBytes()
                        > container.memoryLimitBytes());
        if (invalidContainerMemory) {
            throw new AgentSnapshotRejectedException(
                    "Container memory usage cannot exceed its limit");
        }
    }

    private AgentStatusResponse toConnectedStatus(
            ReceivedAgentSnapshot received,
            Instant now) {
        boolean stale = isSnapshotStale(received, now);
        return new AgentStatusResponse(
                received.snapshot().agentId(),
                received.snapshot().agentVersion(),
                stale ? ConnectionStatus.STALE : ConnectionStatus.CONNECTED,
                received.snapshot().capturedAt(),
                received.receivedAt(),
                stale);
    }

    private AgentStatusResponse toPersistedStatus(
            AgentStatusEntity entity,
            Instant now) {
        boolean stale = isSnapshotStale(
                entity.getLastCapturedAt(),
                entity.getLastSeenAt(),
                now);
        return new AgentStatusResponse(
                entity.getAgentId(),
                entity.getAgentVersion(),
                stale ? ConnectionStatus.STALE : ConnectionStatus.CONNECTED,
                entity.getLastCapturedAt(),
                entity.getLastSeenAt(),
                stale);
    }

    private SystemSummaryResponse toSummary(ReceivedAgentSnapshot received) {
        AgentSnapshotRequest snapshot = received.snapshot();
        long running = snapshot.containers().stream()
                .filter(container -> container.state() == ContainerState.RUNNING)
                .count();
        long unhealthy = snapshot.containers().stream()
                .filter(container -> container.health() == ContainerHealth.UNHEALTHY)
                .count();
        boolean stale = isSnapshotStale(received);
        return new SystemSummaryResponse(
                stale ? ConnectionStatus.STALE.name() : ConnectionStatus.CONNECTED.name(),
                snapshot.capturedAt(),
                stale,
                new HostMetricView(
                        snapshot.host().cpuUsagePercent(),
                        snapshot.host().memoryTotalBytes(),
                        snapshot.host().memoryUsedBytes(),
                        snapshot.host().diskTotalBytes(),
                        snapshot.host().diskUsedBytes(),
                        snapshot.host().uptimeSeconds()),
                new DockerSummaryView(
                        snapshot.containers().size(),
                        Math.toIntExact(running),
                        Math.toIntExact(snapshot.containers().size() - running),
                        Math.toIntExact(unhealthy)));
    }

    private ContainerInventoryResponse toContainerInventory(
            ReceivedAgentSnapshot received) {
        boolean stale = isSnapshotStale(received);
        return new ContainerInventoryResponse(
                stale
                        ? ConnectionStatus.STALE.name()
                        : ConnectionStatus.CONNECTED.name(),
                received.snapshot().capturedAt(),
                stale,
                received.snapshot().containers().stream()
                        .map(ContainerView::from)
                        .toList());
    }

    private boolean isSnapshotStale(
            Instant capturedAt,
            Instant receivedAt,
            Instant now) {
        return capturedAt == null
                || receivedAt == null
                || isStale(capturedAt, now)
                || isStale(receivedAt, now);
    }

    private boolean isSnapshotStale(ReceivedAgentSnapshot received) {
        return isSnapshotStale(received, clock.instant());
    }

    private boolean isSnapshotStale(
            ReceivedAgentSnapshot received,
            Instant now) {
        return isSnapshotStale(
                received.snapshot().capturedAt(),
                received.receivedAt(),
                now);
    }

    private boolean isStale(Instant timestamp, Instant now) {
        return timestamp.isBefore(now.minus(properties.staleAfter()));
    }

    private static AgentSnapshotRequest canonicalize(AgentSnapshotRequest request) {
        return new AgentSnapshotRequest(request.snapshotId(), request.agentId(), request.agentVersion(),
                PostgresqlTimestamp.canonicalize(request.capturedAt()), request.supportsContainerLogs(),
                request.host(), request.containers());
    }

    private boolean sameDatabaseTimestamp(Instant left, Instant right) {
        return right != null && PostgresqlTimestamp.canonicalize(left).equals(PostgresqlTimestamp.canonicalize(right));
    }

    private void publishLatestAfterCommit(ReceivedAgentSnapshot candidate) {
        Runnable publish = () -> latest.accumulateAndGet(
                candidate,
                (current, next) -> current == null
                        || !next.snapshot().capturedAt()
                        .isBefore(current.snapshot().capturedAt())
                        ? next
                        : current);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publish.run();
                    }
                });
    }
}
