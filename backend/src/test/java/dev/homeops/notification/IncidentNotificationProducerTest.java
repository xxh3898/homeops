package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.homeops.monitoring.MonitoredServiceStore.OpenIncident;
import dev.homeops.monitoring.api.MonitoredServiceResponse;
import dev.homeops.notification.config.IncidentNotificationProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class IncidentNotificationProducerTest {
    private static final UUID SERVICE_ID = UUID.fromString("10000000-0000-0000-0000-000000000100");
    private static final UUID INCIDENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000200");
    private static final UUID ROOT_ID = UUID.fromString("30000000-0000-0000-0000-000000000300");
    private static final Instant OPENED_AT = Instant.parse("2026-08-20T00:00:00Z");

    @Mock private NotificationOutbox outbox;
    private IncidentNotificationProducer producer;

    @BeforeEach
    void setUp() {
        producer = new IncidentNotificationProducer(
                outbox,
                new IncidentNotificationProperties(Duration.ofMinutes(15)));
    }

    @ParameterizedTest
    @CsvSource({"INFO, INFO", "WARNING, WARNING", "CRITICAL, CRITICAL"})
    void should_createOpenedRootWithMappedSeverity_when_authorityIsEnabled(
            String serviceSeverity,
            NotificationSeverity expectedSeverity) {
        producer.recordOpened(INCIDENT_ID, service(serviceSeverity), true, OPENED_AT);

        NotificationIntent intent = capturedIntent();
        assertThat(intent.sourceType()).isEqualTo(NotificationSourceType.INCIDENT);
        assertThat(intent.sourceId()).isEqualTo(INCIDENT_ID);
        assertThat(intent.eventType()).isEqualTo(IncidentNotificationProducer.OPENED);
        assertThat(intent.severity()).isEqualTo(expectedSeverity);
        assertThat(intent.parentNotificationId()).isNull();
        assertThat(intent.deduplicationMaterial())
                .isEqualTo("incident:" + INCIDENT_ID + ":INCIDENT_OPENED");
        assertThat(intent.occurredAt()).isEqualTo(OPENED_AT);
    }

    @Test
    void should_notCreateOpenedRoot_when_authorityIsDisabled() {
        producer.recordOpened(INCIDENT_ID, service("WARNING"), false, OPENED_AT);

        verify(outbox, never()).enqueue(any());
    }

    @Test
    void should_notEvaluateRoot_when_escalationThresholdHasNotElapsed() {
        producer.recordContinued(
                incident(), service("WARNING"), true,
                OPENED_AT.plus(Duration.ofMinutes(15)).minusNanos(1));

        verify(outbox, never()).findEvent(any(), any(), any());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void should_createEscalationAtExactThreshold_when_rootIsSent() {
        allowAuthorityAndRoot(NotificationStatus.SENT);
        Instant escalatedAt = OPENED_AT.plus(Duration.ofMinutes(15));

        producer.recordContinued(incident(), service("WARNING"), true, escalatedAt);

        NotificationIntent intent = capturedIntent();
        assertThat(intent.sourceType()).isEqualTo(NotificationSourceType.INCIDENT);
        assertThat(intent.sourceId()).isEqualTo(INCIDENT_ID);
        assertThat(intent.eventType()).isEqualTo(IncidentNotificationProducer.ESCALATED);
        assertThat(intent.severity()).isEqualTo(NotificationSeverity.CRITICAL);
        assertThat(intent.parentNotificationId()).isEqualTo(ROOT_ID);
        assertThat(intent.deduplicationMaterial())
                .isEqualTo("incident:" + INCIDENT_ID + ":INCIDENT_ESCALATED");
        assertThat(intent.occurredAt()).isEqualTo(escalatedAt);
        assertThat(intent.payload().fields())
                .extracting(NotificationField::value)
                .containsExactly("logical-service", "WARNING", "ESCALATED", "15m");
    }

    @ParameterizedTest
    @EnumSource(value = NotificationStatus.class, names = {"SENT"}, mode = EnumSource.Mode.EXCLUDE)
    void should_notCreateEscalation_when_rootIsNotSent(NotificationStatus rootStatus) {
        allowAuthorityAndRoot(rootStatus);

        producer.recordContinued(
                incident(), service("CRITICAL"), true,
                OPENED_AT.plus(Duration.ofMinutes(30)));

        verify(outbox, never()).enqueue(any());
    }

    @Test
    void should_notCreateEscalation_when_historicalIncidentHasNoRoot() {
        when(outbox.findEvent(NotificationSourceType.INCIDENT, INCIDENT_ID,
                IncidentNotificationProducer.OPENED)).thenReturn(Optional.empty());

        producer.recordContinued(
                incident(), service("WARNING"), true,
                OPENED_AT.plus(Duration.ofMinutes(30)));

        verify(outbox, never()).enqueue(any());
    }

    @Test
    void should_createRecoveryWithSentRootParent_when_authorityIsEnabled() {
        allowAuthorityAndRoot(NotificationStatus.SENT);
        Instant recoveredAt = OPENED_AT.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(5));

        producer.recordRecovered(incident(), service("CRITICAL"), true, recoveredAt);

        NotificationIntent intent = capturedIntent();
        assertThat(intent.sourceType()).isEqualTo(NotificationSourceType.INCIDENT);
        assertThat(intent.sourceId()).isEqualTo(INCIDENT_ID);
        assertThat(intent.eventType()).isEqualTo(IncidentNotificationProducer.RECOVERED);
        assertThat(intent.severity()).isEqualTo(NotificationSeverity.RECOVERY);
        assertThat(intent.parentNotificationId()).isEqualTo(ROOT_ID);
        assertThat(intent.deduplicationMaterial())
                .isEqualTo("incident:" + INCIDENT_ID + ":INCIDENT_RECOVERED");
        assertThat(intent.payload().fields())
                .extracting(NotificationField::value)
                .containsExactly("logical-service", "CRITICAL", "RECOVERED", "2h 5m");
    }

    @Test
    void should_notCreateRecovery_when_authorityWasRevoked() {
        producer.recordRecovered(
                incident(), service("WARNING"), false,
                OPENED_AT.plus(Duration.ofHours(1)));

        verify(outbox, never()).findEvent(any(), any(), any());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void should_persistOnlyAllowlistedFields_when_serviceContainsPrivateOrigin() {
        MonitoredServiceResponse service = new MonitoredServiceResponse(
                SERVICE_ID,
                "logical-service",
                "https://private-host.invalid/private-path?token=synthetic",
                "GET",
                200,
                3_000,
                30,
                3,
                2,
                "WARNING",
                true,
                false);

        producer.recordOpened(INCIDENT_ID, service, true, OPENED_AT);

        NotificationIntent intent = capturedIntent();
        assertThat(intent.payload().fields())
                .extracting(NotificationField::name)
                .containsExactly("Service", "Severity", "Status");
        assertThat(intent.payload().fields())
                .extracting(NotificationField::value)
                .containsExactly("logical-service", "WARNING", "OPEN")
                .noneMatch(value -> value.contains("private-host") || value.contains("token"));
    }

    private void allowAuthorityAndRoot(NotificationStatus status) {
        when(outbox.findEvent(NotificationSourceType.INCIDENT, INCIDENT_ID,
                IncidentNotificationProducer.OPENED))
                .thenReturn(Optional.of(new NotificationEventReference(ROOT_ID, status)));
    }

    private NotificationIntent capturedIntent() {
        ArgumentCaptor<NotificationIntent> captor = ArgumentCaptor.forClass(NotificationIntent.class);
        verify(outbox).enqueue(captor.capture());
        return captor.getValue();
    }

    private static OpenIncident incident() {
        return new OpenIncident(INCIDENT_ID, "OPEN", OPENED_AT);
    }

    private static MonitoredServiceResponse service(String severity) {
        return new MonitoredServiceResponse(
                SERVICE_ID,
                "logical-service",
                "https://service.example.invalid/health",
                "GET",
                200,
                3_000,
                30,
                3,
                2,
                severity,
                true,
                false);
    }
}
