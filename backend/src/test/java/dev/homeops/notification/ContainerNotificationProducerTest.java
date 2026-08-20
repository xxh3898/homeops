package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import dev.homeops.agent.api.AgentSnapshotRequest;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerHealth;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerSnapshot;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerState;
import dev.homeops.common.AgentSnapshotRejectedException;
import dev.homeops.notification.config.ContainerNotificationProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContainerNotificationProducerTest {
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String FULL_ID = "0123456789abcdef".repeat(4);
    private static final UUID ROOT_ID = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");

    @Mock private ContainerNotificationStateStore store;
    @Mock private NotificationOutbox outbox;

    private ContainerNotificationProducer producer;

    @BeforeEach
    void setUp() {
        producer = new ContainerNotificationProducer(
                store,
                outbox,
                new ContainerNotificationProperties(
                        Duration.ofSeconds(30), Duration.ofMinutes(5)));
        lenient().when(store.findAllForUpdate("local-mac")).thenReturn(List.of());
    }

    @ParameterizedTest
    @MethodSource("failurePredicates")
    void should_evaluateBoundedFailurePredicate_when_stateAndHealthVary(
            ContainerState state,
            ContainerHealth health,
            boolean expected) {
        assertThat(ContainerNotificationProducer.isFailure(
                container(FULL_ID, state, health, true))).isEqualTo(expected);
    }

    @Test
    void should_insertFailedBaselineWithoutIntent_when_firstObservationIsOptedIn() {
        AgentSnapshotRequest snapshot = snapshot(
                NOW, FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true);

        producer.recordCurrentSnapshot(snapshot);

        ArgumentCaptor<ContainerNotificationState> captured =
                ArgumentCaptor.forClass(ContainerNotificationState.class);
        verify(store).insert(captured.capture());
        assertThat(captured.getValue().failureStartedAt()).isEqualTo(NOW);
        assertThat(captured.getValue().activeEpisodeId()).isNull();
        assertThat(captured.getValue().notificationsAllowed()).isTrue();
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void should_createCriticalRootAtExactThreshold_withoutRawDockerMetadata() {
        ContainerNotificationState prior = state(
                NOW.minusSeconds(30), null, null, true, FULL_ID,
                ContainerState.EXITED, ContainerHealth.NONE, NOW.minusSeconds(1));
        when(store.findAllForUpdate("local-mac")).thenReturn(List.of(prior));
        when(outbox.enqueue(any())).thenReturn(ROOT_ID);

        producer.recordCurrentSnapshot(snapshot(
                NOW, FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true));

        ArgumentCaptor<NotificationIntent> intent =
                ArgumentCaptor.forClass(NotificationIntent.class);
        verify(outbox).enqueue(intent.capture());
        assertThat(intent.getValue().sourceType()).isEqualTo(NotificationSourceType.CONTAINER);
        assertThat(intent.getValue().severity()).isEqualTo(NotificationSeverity.CRITICAL);
        assertThat(intent.getValue().eventType()).isEqualTo("CONTAINER_FAILED");
        assertThat(intent.getValue().parentNotificationId()).isNull();
        assertThat(intent.getValue().deduplicationMaterial())
                .isEqualTo("container:" + intent.getValue().sourceId() + ":CONTAINER_FAILED");
        assertThat(intent.getValue().payload().fields())
                .extracting(NotificationField::value)
                .contains("api", "project", "EXITED", "NONE", "FAILED", "30s")
                .allSatisfy(value -> assertThat(value)
                        .doesNotContain(FULL_ID, "private.example.invalid", "raw status"));

        ArgumentCaptor<ContainerNotificationState> updated =
                ArgumentCaptor.forClass(ContainerNotificationState.class);
        verify(store).update(updated.capture());
        assertThat(updated.getValue().activeEpisodeId())
                .isEqualTo(intent.getValue().sourceId());
        assertThat(updated.getValue().lastRootCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void should_waitUntilCooldownExpires_when_newFailureAlreadyPassedThreshold() {
        ContainerNotificationState prior = state(
                NOW.minusSeconds(40), null, NOW.minusSeconds(299), true, FULL_ID,
                ContainerState.EXITED, ContainerHealth.NONE, NOW.minusSeconds(1));
        when(store.findAllForUpdate("local-mac")).thenReturn(List.of(prior));

        producer.recordCurrentSnapshot(snapshot(
                NOW, FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true));

        verify(outbox, never()).enqueue(any());
        ArgumentCaptor<ContainerNotificationState> updated =
                ArgumentCaptor.forClass(ContainerNotificationState.class);
        verify(store).update(updated.capture());
        assertThat(updated.getValue().failureStartedAt()).isEqualTo(NOW.minusSeconds(40));
        assertThat(updated.getValue().activeEpisodeId()).isNull();
    }

    @Test
    void should_allowRootAtExactCooldownBoundary_when_failureStillPersists() {
        ContainerNotificationState prior = state(
                NOW.minusSeconds(40), null, NOW.minusSeconds(300), true, FULL_ID,
                ContainerState.EXITED, ContainerHealth.NONE, NOW.minusSeconds(1));
        when(store.findAllForUpdate("local-mac")).thenReturn(List.of(prior));

        producer.recordCurrentSnapshot(snapshot(
                NOW, FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true));

        verify(outbox).enqueue(any(NotificationIntent.class));
    }

    @Test
    void should_createRecoveryChildOnlyWhenRootWasSent() {
        UUID episode = UUID.randomUUID();
        ContainerNotificationState prior = state(
                NOW.minusSeconds(60), episode, NOW.minusSeconds(30), true, FULL_ID,
                ContainerState.EXITED, ContainerHealth.NONE, NOW.minusSeconds(1));
        when(store.findAllForUpdate("local-mac")).thenReturn(List.of(prior));
        when(outbox.findEvent(NotificationSourceType.CONTAINER, episode, "CONTAINER_FAILED"))
                .thenReturn(java.util.Optional.of(
                        new NotificationEventReference(ROOT_ID, NotificationStatus.SENT)));

        producer.recordCurrentSnapshot(snapshot(
                NOW, FULL_ID, ContainerState.RUNNING, ContainerHealth.HEALTHY, true));

        ArgumentCaptor<NotificationIntent> intent =
                ArgumentCaptor.forClass(NotificationIntent.class);
        verify(outbox).enqueue(intent.capture());
        assertThat(intent.getValue().eventType()).isEqualTo("CONTAINER_RECOVERED");
        assertThat(intent.getValue().severity()).isEqualTo(NotificationSeverity.RECOVERY);
        assertThat(intent.getValue().sourceId()).isEqualTo(episode);
        assertThat(intent.getValue().parentNotificationId()).isEqualTo(ROOT_ID);
        ArgumentCaptor<ContainerNotificationState> updated =
                ArgumentCaptor.forClass(ContainerNotificationState.class);
        verify(store).update(updated.capture());
        assertThat(updated.getValue().failureStartedAt()).isNull();
        assertThat(updated.getValue().activeEpisodeId()).isNull();
    }

    @Test
    void should_clearEpisodeWithoutRecovery_whenRootWasNotSent() {
        UUID episode = UUID.randomUUID();
        ContainerNotificationState prior = state(
                NOW.minusSeconds(60), episode, NOW.minusSeconds(30), true, FULL_ID,
                ContainerState.EXITED, ContainerHealth.NONE, NOW.minusSeconds(1));
        when(store.findAllForUpdate("local-mac")).thenReturn(List.of(prior));
        when(outbox.findEvent(NotificationSourceType.CONTAINER, episode, "CONTAINER_FAILED"))
                .thenReturn(java.util.Optional.of(
                        new NotificationEventReference(ROOT_ID, NotificationStatus.SUPPRESSED)));

        producer.recordCurrentSnapshot(snapshot(
                NOW, FULL_ID, ContainerState.RUNNING, ContainerHealth.HEALTHY, true));

        verify(outbox, never()).enqueue(any());
        ArgumentCaptor<ContainerNotificationState> updated =
                ArgumentCaptor.forClass(ContainerNotificationState.class);
        verify(store).update(updated.capture());
        assertThat(updated.getValue().activeEpisodeId()).isNull();
    }

    @Test
    void should_resetAuthorityWithoutRecovery_whenOptInIsRevoked() {
        UUID episode = UUID.randomUUID();
        ContainerNotificationState prior = state(
                NOW.minusSeconds(60), episode, NOW.minusSeconds(30), true, FULL_ID,
                ContainerState.EXITED, ContainerHealth.NONE, NOW.minusSeconds(1));
        when(store.findAllForUpdate("local-mac")).thenReturn(List.of(prior));

        producer.recordCurrentSnapshot(snapshot(
                NOW, FULL_ID, ContainerState.RUNNING, ContainerHealth.HEALTHY, false));

        verify(outbox, never()).findEvent(any(), any(), any());
        verify(outbox, never()).enqueue(any());
        ArgumentCaptor<ContainerNotificationState> updated =
                ArgumentCaptor.forClass(ContainerNotificationState.class);
        verify(store).update(updated.capture());
        assertThat(updated.getValue().notificationsAllowed()).isFalse();
        assertThat(updated.getValue().failureStartedAt()).isNull();
        assertThat(updated.getValue().activeEpisodeId()).isNull();
    }

    @Test
    void should_startNewBaselineWithoutRecovery_whenInstanceChanges() {
        UUID episode = UUID.randomUUID();
        ContainerNotificationState prior = state(
                NOW.minusSeconds(60), episode, NOW.minusSeconds(30), true, FULL_ID,
                ContainerState.EXITED, ContainerHealth.NONE, NOW.minusSeconds(1));
        when(store.findAllForUpdate("local-mac")).thenReturn(List.of(prior));
        String newId = "fedcba9876543210".repeat(4);

        producer.recordCurrentSnapshot(snapshot(
                NOW, newId, ContainerState.EXITED, ContainerHealth.NONE, true));

        verify(outbox, never()).findEvent(any(), any(), any());
        verify(outbox, never()).enqueue(any());
        ArgumentCaptor<ContainerNotificationState> updated =
                ArgumentCaptor.forClass(ContainerNotificationState.class);
        verify(store).update(updated.capture());
        assertThat(updated.getValue().instanceFingerprint())
                .isNotEqualTo(prior.instanceFingerprint());
        assertThat(updated.getValue().failureStartedAt()).isEqualTo(NOW);
        assertThat(updated.getValue().activeEpisodeId()).isNull();
        assertThat(updated.getValue().lastRootCreatedAt())
                .isEqualTo(prior.lastRootCreatedAt());
    }

    @Test
    void should_deleteMissingCurrentStateWithoutNotification_when_inventoryIsComplete() {
        ContainerNotificationState prior = state(
                NOW.minusSeconds(60), UUID.randomUUID(), NOW.minusSeconds(30), true, FULL_ID,
                ContainerState.EXITED, ContainerHealth.NONE, NOW.minusSeconds(1));
        when(store.findAllForUpdate("local-mac")).thenReturn(List.of(prior));

        producer.recordCurrentSnapshot(snapshot(NOW, List.of()));

        verify(store).deleteMissingIfOlder(prior.id(), NOW);
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void should_ignoreEqualObservation_when_stateAlreadyHasSameCaptureTime() {
        ContainerNotificationState prior = state(
                NOW.minusSeconds(30), null, null, true, FULL_ID,
                ContainerState.EXITED, ContainerHealth.NONE, NOW);
        when(store.findAllForUpdate("local-mac")).thenReturn(List.of(prior));

        producer.recordCurrentSnapshot(snapshot(
                NOW, FULL_ID, ContainerState.RUNNING, ContainerHealth.HEALTHY, true));

        verify(store, never()).update(any());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void should_failClosedWithoutMutation_when_snapshotDuplicatesLogicalIdentity() {
        ContainerSnapshot container = container(
                FULL_ID, ContainerState.RUNNING, ContainerHealth.HEALTHY, true);
        AgentSnapshotRequest snapshot = snapshot(NOW, List.of(container, container));

        assertThatThrownBy(() -> producer.recordCurrentSnapshot(snapshot))
                .isInstanceOf(AgentSnapshotRejectedException.class)
                .hasMessage("Container logical identity is duplicated")
                .hasMessageNotContaining(FULL_ID);
        verify(outbox, never()).enqueue(any());
    }

    private static Stream<Arguments> failurePredicates() {
        return Stream.of(
                Arguments.of(ContainerState.RUNNING, ContainerHealth.HEALTHY, false),
                Arguments.of(ContainerState.RUNNING, ContainerHealth.STARTING, false),
                Arguments.of(ContainerState.RUNNING, ContainerHealth.NONE, false),
                Arguments.of(ContainerState.RUNNING, ContainerHealth.UNKNOWN, false),
                Arguments.of(ContainerState.RUNNING, ContainerHealth.UNHEALTHY, true),
                Arguments.of(ContainerState.EXITED, ContainerHealth.HEALTHY, true),
                Arguments.of(ContainerState.UNKNOWN, ContainerHealth.UNKNOWN, true));
    }

    private static AgentSnapshotRequest snapshot(
            Instant capturedAt,
            String id,
            ContainerState state,
            ContainerHealth health,
            boolean allowed) {
        return snapshot(capturedAt, List.of(container(id, state, health, allowed)));
    }

    private static AgentSnapshotRequest snapshot(
            Instant capturedAt,
            List<ContainerSnapshot> containers) {
        return new AgentSnapshotRequest(
                UUID.randomUUID(),
                "local-mac",
                "v1",
                capturedAt,
                false,
                new AgentSnapshotRequest.HostSnapshot(10, 100, 50, 100, 50, 10),
                containers);
    }

    private static ContainerSnapshot container(
            String id,
            ContainerState state,
            ContainerHealth health,
            boolean allowed) {
        return new ContainerSnapshot(
                id,
                "api",
                "project",
                "private.example.invalid/image:tag",
                state,
                health,
                "raw status with token=synthetic",
                NOW.minusSeconds(60),
                0,
                12.5,
                100L,
                200L,
                List.of(),
                false,
                false,
                allowed);
    }

    private static ContainerNotificationState state(
            Instant failureStartedAt,
            UUID activeEpisodeId,
            Instant lastRootCreatedAt,
            boolean allowed,
            String id,
            ContainerState state,
            ContainerHealth health,
            Instant capturedAt) {
        var identity = ContainerNotificationIdentity.from(container(id, state, health, allowed));
        return new ContainerNotificationState(
                UUID.randomUUID(),
                "local-mac",
                identity.logicalHash(),
                identity.displayName(),
                identity.composeProject(),
                identity.instanceFingerprint(),
                allowed,
                state,
                health,
                UUID.randomUUID(),
                capturedAt,
                failureStartedAt,
                activeEpisodeId,
                lastRootCreatedAt);
    }
}
