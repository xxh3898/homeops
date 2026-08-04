package dev.homeops.agent;

import dev.homeops.agent.api.AgentSnapshotAcceptedResponse;
import dev.homeops.agent.api.AgentSnapshotRequest;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerHealth;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerState;
import dev.homeops.agent.api.AgentStatusResponse;
import dev.homeops.agent.api.AgentStatusResponse.ConnectionStatus;
import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.agent.domain.ReceivedAgentSnapshot;
import dev.homeops.agent.persistence.AgentStatusEntity;
import dev.homeops.agent.persistence.AgentStatusRepository;
import dev.homeops.agent.persistence.HostMetricAggregateEntity;
import dev.homeops.agent.persistence.HostMetricAggregateRepository;
import dev.homeops.common.AgentSnapshotRejectedException;
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
    private final HostMetricAggregateRepository metricRepository;
    private final Clock clock;
    private final AtomicReference<ReceivedAgentSnapshot> latest =
            new AtomicReference<>();

    @Autowired
    public AgentSnapshotService(
            HomeOpsAgentProperties properties,
            AgentStatusRepository agentStatusRepository,
            HostMetricAggregateRepository metricRepository) {
        this(properties, agentStatusRepository, metricRepository, Clock.systemUTC());
    }

    AgentSnapshotService(
            HomeOpsAgentProperties properties,
            AgentStatusRepository agentStatusRepository,
            HostMetricAggregateRepository metricRepository,
            Clock clock) {
        this.properties = properties;
        this.agentStatusRepository = agentStatusRepository;
        this.metricRepository = metricRepository;
        this.clock = clock;
    }

    @Transactional
    public AgentSnapshotAcceptedResponse accept(AgentSnapshotRequest request) {
        Instant receivedAt = clock.instant();
        validate(request, receivedAt);

        AgentStatusEntity status = agentStatusRepository
                .findById(request.agentId())
                .orElseGet(() -> AgentStatusEntity.create(request.agentId()));
        if (status.hasProcessed(request.snapshotId())) {
            if (!sameDatabaseTimestamp(
                    request.capturedAt(),
                    status.getLastCapturedAt())) {
                throw new AgentSnapshotRejectedException(
                        "Snapshot identifier conflicts with a prior capture time");
            }
            publishLatestAfterCommit(new ReceivedAgentSnapshot(request, receivedAt));
            return new AgentSnapshotAcceptedResponse(
                    request.snapshotId(),
                    receivedAt,
                    true);
        }

        Instant bucket = request.capturedAt().truncatedTo(ChronoUnit.MINUTES);
        Optional<HostMetricAggregateEntity> existingAggregate = metricRepository
                .findByAgentIdAndBucketStart(request.agentId(), bucket);
        HostMetricAggregateEntity aggregate;
        if (existingAggregate.isPresent()) {
            aggregate = existingAggregate.get();
            aggregate.addSample(request.host());
        } else {
            aggregate = HostMetricAggregateEntity.create(
                    request.agentId(),
                    bucket,
                    request.host());
        }
        metricRepository.save(aggregate);

        status.recordSnapshot(
                request.snapshotId(),
                request.agentVersion(),
                request.capturedAt(),
                receivedAt);
        agentStatusRepository.save(status);
        publishLatestAfterCommit(new ReceivedAgentSnapshot(request, receivedAt));

        return new AgentSnapshotAcceptedResponse(
                request.snapshotId(),
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

    public List<ContainerView> containers() {
        return latest()
                .map(received -> received.snapshot().containers().stream()
                        .map(ContainerView::from)
                        .toList())
                .orElseGet(List::of);
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
        boolean stale = isStale(received.receivedAt(), now);
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
        boolean stale = entity.getLastSeenAt() == null
                || isStale(entity.getLastSeenAt(), now);
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
        boolean stale = isStale(received.receivedAt(), clock.instant());
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

    private boolean isStale(Instant lastSeen, Instant now) {
        return lastSeen.isBefore(now.minus(properties.staleAfter()));
    }

    private boolean sameDatabaseTimestamp(Instant left, Instant right) {
        return right != null
                && left.truncatedTo(ChronoUnit.MICROS)
                .equals(right.truncatedTo(ChronoUnit.MICROS));
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
