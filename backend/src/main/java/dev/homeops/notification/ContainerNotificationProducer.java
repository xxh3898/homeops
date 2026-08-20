package dev.homeops.notification;

import dev.homeops.agent.api.AgentSnapshotRequest;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerHealth;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerSnapshot;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerState;
import dev.homeops.common.AgentSnapshotRejectedException;
import dev.homeops.notification.ContainerNotificationIdentity.Identity;
import dev.homeops.notification.config.ContainerNotificationProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ContainerNotificationProducer {
    static final String FAILED = "CONTAINER_FAILED";
    static final String RECOVERED = "CONTAINER_RECOVERED";
    private static final Duration MAXIMUM_REPORTED_DURATION = Duration.ofDays(30);

    private final ContainerNotificationStateStore store;
    private final NotificationOutbox outbox;
    private final Duration failureAfter;
    private final Duration realertCooldown;

    ContainerNotificationProducer(
            ContainerNotificationStateStore store,
            NotificationOutbox outbox,
            ContainerNotificationProperties properties) {
        this.store = store;
        this.outbox = outbox;
        this.failureAfter = properties.failureAfter();
        this.realertCooldown = properties.realertCooldown();
    }

    public void recordCurrentSnapshot(AgentSnapshotRequest snapshot) {
        List<ContainerNotificationState> persisted = store.findAllForUpdate(snapshot.agentId());
        Map<String, ContainerNotificationState> byIdentity = new HashMap<>();
        persisted.forEach(state -> byIdentity.put(state.logicalIdentityHash(), state));
        Set<String> observed = new HashSet<>();

        for (ContainerSnapshot container : snapshot.containers()) {
            Identity identity = ContainerNotificationIdentity.from(container);
            if (!observed.add(identity.logicalHash())) {
                throw new AgentSnapshotRejectedException(
                        "Container logical identity is duplicated");
            }
            ContainerNotificationState prior = byIdentity.get(identity.logicalHash());
            if (prior == null) {
                store.insert(baseline(snapshot, container, identity, null));
                continue;
            }
            requireMatchingIdentity(prior, identity);
            if (!snapshot.capturedAt().isAfter(prior.lastCapturedAt())) {
                continue;
            }
            transition(snapshot, container, identity, prior);
        }

        for (ContainerNotificationState state : persisted) {
            if (!observed.contains(state.logicalIdentityHash())) {
                store.deleteMissingIfOlder(state.id(), snapshot.capturedAt());
            }
        }
    }

    static boolean isFailure(ContainerSnapshot container) {
        return container.state() != ContainerState.RUNNING
                || container.health() == ContainerHealth.UNHEALTHY;
    }

    private void transition(
            AgentSnapshotRequest snapshot,
            ContainerSnapshot container,
            Identity identity,
            ContainerNotificationState prior) {
        if (!prior.instanceFingerprint().equals(identity.instanceFingerprint())
                || (!prior.notificationsAllowed() && container.notificationsAllowed())) {
            store.update(baseline(snapshot, container, identity, prior));
            return;
        }
        if (!container.notificationsAllowed()) {
            store.update(observedState(snapshot, container, identity, prior, null, null,
                    prior.lastRootCreatedAt()));
            return;
        }
        if (!isFailure(container)) {
            recordRecoveryIfEligible(snapshot, container, identity, prior);
            return;
        }

        Instant failureStartedAt = prior.failureStartedAt();
        if (failureStartedAt == null) {
            store.update(observedState(snapshot, container, identity, prior,
                    snapshot.capturedAt(), null, prior.lastRootCreatedAt()));
            return;
        }
        if (prior.activeEpisodeId() != null
                || snapshot.capturedAt().isBefore(failureStartedAt.plus(failureAfter))
                || (prior.lastRootCreatedAt() != null
                && snapshot.capturedAt().isBefore(
                prior.lastRootCreatedAt().plus(realertCooldown)))) {
            store.update(observedState(snapshot, container, identity, prior,
                    failureStartedAt, prior.activeEpisodeId(), prior.lastRootCreatedAt()));
            return;
        }

        UUID episodeId = UUID.randomUUID();
        outbox.enqueue(intent(
                episodeId,
                null,
                FAILED,
                NotificationSeverity.CRITICAL,
                container,
                identity,
                failureStartedAt,
                snapshot.capturedAt(),
                "FAILED"));
        store.update(observedState(snapshot, container, identity, prior,
                failureStartedAt, episodeId, snapshot.capturedAt()));
    }

    private void recordRecoveryIfEligible(
            AgentSnapshotRequest snapshot,
            ContainerSnapshot container,
            Identity identity,
            ContainerNotificationState prior) {
        if (prior.activeEpisodeId() != null) {
            sentRoot(prior.activeEpisodeId()).ifPresent(root -> outbox.enqueue(intent(
                    prior.activeEpisodeId(),
                    root.id(),
                    RECOVERED,
                    NotificationSeverity.RECOVERY,
                    container,
                    identity,
                    prior.failureStartedAt(),
                    snapshot.capturedAt(),
                    "RECOVERED")));
        }
        store.update(observedState(snapshot, container, identity, prior,
                null, null, prior.lastRootCreatedAt()));
    }

    private Optional<NotificationEventReference> sentRoot(UUID episodeId) {
        return outbox.findEvent(NotificationSourceType.CONTAINER, episodeId, FAILED)
                .filter(root -> root.status() == NotificationStatus.SENT);
    }

    private static ContainerNotificationState baseline(
            AgentSnapshotRequest snapshot,
            ContainerSnapshot container,
            Identity identity,
            ContainerNotificationState prior) {
        Instant failureStartedAt = container.notificationsAllowed() && isFailure(container)
                ? snapshot.capturedAt() : null;
        return new ContainerNotificationState(
                prior == null ? UUID.randomUUID() : prior.id(),
                snapshot.agentId(),
                identity.logicalHash(),
                identity.displayName(),
                identity.composeProject(),
                identity.instanceFingerprint(),
                container.notificationsAllowed(),
                container.state(),
                container.health(),
                snapshot.snapshotId(),
                snapshot.capturedAt(),
                failureStartedAt,
                null,
                prior == null ? null : prior.lastRootCreatedAt());
    }

    private static ContainerNotificationState observedState(
            AgentSnapshotRequest snapshot,
            ContainerSnapshot container,
            Identity identity,
            ContainerNotificationState prior,
            Instant failureStartedAt,
            UUID activeEpisodeId,
            Instant lastRootCreatedAt) {
        return new ContainerNotificationState(
                prior.id(),
                prior.agentId(),
                prior.logicalIdentityHash(),
                identity.displayName(),
                identity.composeProject(),
                identity.instanceFingerprint(),
                container.notificationsAllowed(),
                container.state(),
                container.health(),
                snapshot.snapshotId(),
                snapshot.capturedAt(),
                failureStartedAt,
                activeEpisodeId,
                lastRootCreatedAt);
    }

    private static NotificationIntent intent(
            UUID episodeId,
            UUID parentNotificationId,
            String eventCode,
            NotificationSeverity severity,
            ContainerSnapshot container,
            Identity identity,
            Instant failureStartedAt,
            Instant occurredAt,
            String lifecycleStatus) {
        List<NotificationField> fields = new ArrayList<>();
        fields.add(new NotificationField("Container", identity.displayName(), true));
        if (identity.composeProject() != null) {
            fields.add(new NotificationField("Project", identity.composeProject(), true));
        }
        fields.add(new NotificationField("State", container.state().name(), true));
        fields.add(new NotificationField("Health", container.health().name(), true));
        fields.add(new NotificationField("Status", lifecycleStatus, true));
        fields.add(new NotificationField(
                "Duration", durationSummary(failureStartedAt, occurredAt), true));
        boolean recovery = eventCode.equals(RECOVERED);
        return new NotificationIntent(
                NotificationSourceType.CONTAINER,
                episodeId,
                severity,
                eventCode,
                "container:" + episodeId + ":" + eventCode,
                parentNotificationId,
                occurredAt,
                new NotificationPayload(
                        eventCode,
                        recovery ? "Container recovered" : "Container failure detected",
                        recovery
                                ? "An opted-in container returned to its healthy state."
                                : "An opted-in container remained in a failure state.",
                        fields,
                        occurredAt));
    }

    private static String durationSummary(Instant startedAt, Instant eventAt) {
        Duration elapsed = startedAt == null
                ? Duration.ZERO : Duration.between(startedAt, eventAt);
        if (elapsed.isNegative()) {
            elapsed = Duration.ZERO;
        }
        if (elapsed.compareTo(MAXIMUM_REPORTED_DURATION) > 0) {
            return "30d+";
        }
        long seconds = elapsed.toSeconds();
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;
        if (hours < 24) {
            return hours + "h " + remainingMinutes + "m";
        }
        return (hours / 24) + "d " + (hours % 24) + "h";
    }

    private static void requireMatchingIdentity(
            ContainerNotificationState prior,
            Identity identity) {
        if (!prior.displayName().equals(identity.displayName())
                || !java.util.Objects.equals(
                prior.composeProject(), identity.composeProject())) {
            throw new AgentSnapshotRejectedException(
                    "Container logical identity conflicts with persisted state");
        }
    }
}
