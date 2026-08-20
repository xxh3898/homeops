package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentLifecycleNotificationProducerTest {
    private static final String AGENT_ID = "local-mac";
    private static final UUID STALE_SNAPSHOT_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000043");
    private static final UUID VERSION_SNAPSHOT_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000043");
    private static final UUID ROOT_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000043");
    private static final Instant STALE_SINCE = Instant.parse("2026-08-20T01:00:00Z");
    private static final Instant OBSERVED_AT = STALE_SINCE.plusSeconds(31);

    @Mock private NotificationOutbox outbox;
    private AgentLifecycleNotificationProducer producer;

    @BeforeEach
    void createProducer() {
        producer = new AgentLifecycleNotificationProducer(outbox);
    }

    @Test
    void should_createCriticalStaleRootWithDeterministicEpisodeIdentity_when_staleEpisodeIsObserved() {
        producer.recordStale(
                STALE_SNAPSHOT_ID, AGENT_ID, "v1", STALE_SINCE, OBSERVED_AT);

        NotificationIntent intent = capturedIntent();
        assertThat(intent.sourceType()).isEqualTo(NotificationSourceType.AGENT);
        assertThat(intent.sourceId()).isEqualTo(STALE_SNAPSHOT_ID);
        assertThat(intent.eventType()).isEqualTo(AgentLifecycleNotificationProducer.STALE);
        assertThat(intent.severity()).isEqualTo(NotificationSeverity.CRITICAL);
        assertThat(intent.parentNotificationId()).isNull();
        assertThat(intent.deduplicationMaterial())
                .isEqualTo("agent:" + STALE_SNAPSHOT_ID + ":AGENT_STALE");
        assertThat(intent.payload().fields())
                .extracting(NotificationField::value)
                .containsExactly(AGENT_ID, "v1", "STALE", "31s");
    }

    @Test
    void should_createRecoveryChild_when_staleRootWasSent() {
        when(outbox.findEvent(
                NotificationSourceType.AGENT,
                STALE_SNAPSHOT_ID,
                AgentLifecycleNotificationProducer.STALE))
                .thenReturn(Optional.of(new NotificationEventReference(ROOT_ID, NotificationStatus.SENT)));

        producer.recordRecovered(
                STALE_SNAPSHOT_ID,
                AGENT_ID,
                "v2",
                STALE_SINCE,
                STALE_SINCE.plus(Duration.ofMinutes(2)));

        NotificationIntent intent = capturedIntent();
        assertThat(intent.sourceId()).isEqualTo(STALE_SNAPSHOT_ID);
        assertThat(intent.eventType()).isEqualTo(AgentLifecycleNotificationProducer.RECOVERED);
        assertThat(intent.severity()).isEqualTo(NotificationSeverity.RECOVERY);
        assertThat(intent.parentNotificationId()).isEqualTo(ROOT_ID);
        assertThat(intent.payload().fields())
                .extracting(NotificationField::value)
                .containsExactly(AGENT_ID, "v2", "RECOVERED", "2m");
    }

    @ParameterizedTest
    @EnumSource(value = NotificationStatus.class, names = {"SENT"}, mode = EnumSource.Mode.EXCLUDE)
    void should_notCreateRecovery_when_staleRootIsNotSent(NotificationStatus status) {
        when(outbox.findEvent(
                NotificationSourceType.AGENT,
                STALE_SNAPSHOT_ID,
                AgentLifecycleNotificationProducer.STALE))
                .thenReturn(Optional.of(new NotificationEventReference(ROOT_ID, status)));

        producer.recordRecovered(
                STALE_SNAPSHOT_ID, AGENT_ID, "v2", STALE_SINCE, OBSERVED_AT);

        verify(outbox, never()).enqueue(any());
    }

    @Test
    void should_notCreateRecovery_when_historicalStaleEpisodeHasNoRoot() {
        when(outbox.findEvent(
                NotificationSourceType.AGENT,
                STALE_SNAPSHOT_ID,
                AgentLifecycleNotificationProducer.STALE))
                .thenReturn(Optional.empty());

        producer.recordRecovered(
                STALE_SNAPSHOT_ID, AGENT_ID, "v2", STALE_SINCE, OBSERVED_AT);

        verify(outbox, never()).enqueue(any());
    }

    @Test
    void should_createInfoVersionChange_when_currentVersionChanges() {
        producer.recordVersionChanged(VERSION_SNAPSHOT_ID, AGENT_ID, "v2", OBSERVED_AT);

        NotificationIntent intent = capturedIntent();
        assertThat(intent.sourceType()).isEqualTo(NotificationSourceType.AGENT);
        assertThat(intent.sourceId()).isEqualTo(VERSION_SNAPSHOT_ID);
        assertThat(intent.eventType()).isEqualTo(AgentLifecycleNotificationProducer.VERSION_CHANGED);
        assertThat(intent.severity()).isEqualTo(NotificationSeverity.INFO);
        assertThat(intent.deduplicationMaterial())
                .isEqualTo("agent:" + VERSION_SNAPSHOT_ID + ":AGENT_VERSION_CHANGED");
        assertThat(intent.payload().fields())
                .extracting(NotificationField::value)
                .containsExactly(AGENT_ID, "v2", "VERSION_CHANGED");
    }

    @Test
    void should_includeOnlyTypedAllowlistedAgentLifecycleFields_when_buildingPayload() {
        String boundedVersion = "v2-build-20260820";

        producer.recordStale(
                STALE_SNAPSHOT_ID, AGENT_ID, boundedVersion, STALE_SINCE, OBSERVED_AT);

        NotificationIntent intent = capturedIntent();
        assertThat(intent.payload().fields())
                .extracting(NotificationField::name)
                .containsExactly("Agent", "Version", "Status", "Duration");
        assertThat(intent.payload().fields())
                .extracting(NotificationField::value)
                .containsExactly(AGENT_ID, boundedVersion, "STALE", "31s");
        assertThat(intent.payload().summary())
                .doesNotContain("host", "tailnet", "docker", "metric", "path", "credential");
    }

    private NotificationIntent capturedIntent() {
        ArgumentCaptor<NotificationIntent> captor = ArgumentCaptor.forClass(NotificationIntent.class);
        verify(outbox).enqueue(captor.capture());
        return captor.getValue();
    }
}
