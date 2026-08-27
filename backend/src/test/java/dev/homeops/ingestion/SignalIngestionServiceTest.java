package dev.homeops.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.homeops.common.EventKeyConflictException;
import dev.homeops.common.InvalidIngestionStateTransitionException;
import dev.homeops.common.SignalIngestionConflictException;
import dev.homeops.ingestion.api.IngestionAcceptedResponse;
import dev.homeops.ingestion.api.SignalIngestionRequest;
import dev.homeops.ingestion.api.SignalIngestionRequest.SignalStatus;
import dev.homeops.ingestion.api.SignalIngestionRequest.SignalType;
import dev.homeops.ingestion.persistence.SignalIngestionStore;
import dev.homeops.ingestion.persistence.SignalIngestionStore.StoredSignalEpisode;
import dev.homeops.ingestion.persistence.SignalIngestionStore.StoredSignalEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SignalIngestionServiceTest {
    private static final UUID EPISODE_ID = UUID.fromString("10000000-0000-0000-0000-000000000101");
    private static final UUID INCIDENT_ID = UUID.fromString("20000000-0000-0000-0000-000000000101");
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-27T01:02:03.123456Z");

    @Mock private SignalIngestionStore signals;
    @Spy private final IngestionDigest digest = new IngestionDigest();
    @InjectMocks private SignalIngestionService service;

    @Test
    void should_returnExactDuplicate_when_eventDigestMatches() {
        SignalIngestionRequest request = disk("disk-alert-1", SignalStatus.ALERT);
        String requestDigest = digest.calculate(request);
        when(signals.findEvent(request.eventKey())).thenReturn(Optional.of(
                new StoredSignalEvent(EPISODE_ID, requestDigest)));

        IngestionAcceptedResponse response = service.accept(request);

        assertThat(response).isEqualTo(new IngestionAcceptedResponse(EPISODE_ID, true));
        verify(signals, never()).insertAlertEpisode(any());
    }

    @Test
    void should_rejectConflict_when_eventKeyPayloadDiffers() {
        SignalIngestionRequest request = disk("disk-alert-1", SignalStatus.ALERT);
        when(signals.findEvent(request.eventKey())).thenReturn(Optional.of(
                new StoredSignalEvent(EPISODE_ID, "0".repeat(64))));

        assertThatThrownBy(() -> service.accept(request))
                .isInstanceOf(EventKeyConflictException.class);

        verify(signals, never()).insertAlertEpisode(any());
    }

    @Test
    void should_createEpisodeAndEvent_when_alertWins() {
        SignalIngestionRequest request = disk("disk-alert-1", SignalStatus.ALERT);
        when(signals.findEvent(request.eventKey())).thenReturn(Optional.empty());
        when(signals.findEpisode(request.episodeKey())).thenReturn(Optional.empty());
        when(signals.insertAlertEpisode(request)).thenReturn(Optional.of(EPISODE_ID));
        when(signals.insertEventIfAbsent(eq(EPISODE_ID), eq(request), any())).thenReturn(true);

        IngestionAcceptedResponse response = service.accept(request);

        assertThat(response).isEqualTo(new IngestionAcceptedResponse(EPISODE_ID, false));
        verify(signals).insertEventIfAbsent(eq(EPISODE_ID), eq(request), any());
    }

    @Test
    void should_rejectSecondAlert_when_episodeIsAlreadyActive() {
        SignalIngestionRequest request = disk("disk-alert-2", SignalStatus.ALERT);
        when(signals.findEvent(request.eventKey())).thenReturn(Optional.empty());
        when(signals.findEpisode(request.episodeKey())).thenReturn(Optional.of(activeEpisode()));

        assertThatThrownBy(() -> service.accept(request))
                .isInstanceOf(InvalidIngestionStateTransitionException.class);

        verify(signals, never()).insertAlertEpisode(any());
    }

    @Test
    void should_recoverSameEpisode_when_activeTransitionWins() {
        SignalIngestionRequest request = disk("disk-recovered-1", SignalStatus.RECOVERED);
        when(signals.findEvent(request.eventKey())).thenReturn(Optional.empty());
        when(signals.findEpisode(request.episodeKey())).thenReturn(Optional.of(activeEpisode()));
        when(signals.recoverEpisode(activeEpisode(), request)).thenReturn(true);
        when(signals.insertEventIfAbsent(eq(EPISODE_ID), eq(request), any())).thenReturn(true);

        IngestionAcceptedResponse response = service.accept(request);

        assertThat(response).isEqualTo(new IngestionAcceptedResponse(EPISODE_ID, false));
        verify(signals).recoverEpisode(activeEpisode(), request);
    }

    @Test
    void should_rejectRecovery_when_episodeDoesNotExist() {
        SignalIngestionRequest request = disk("disk-recovered-1", SignalStatus.RECOVERED);
        when(signals.findEvent(request.eventKey())).thenReturn(Optional.empty());
        when(signals.findEpisode(request.episodeKey())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.accept(request))
                .isInstanceOf(SignalIngestionConflictException.class);

        verify(signals, never()).recoverEpisode(any(), any());
    }

    @Test
    void should_rejectRecovery_when_projectDoesNotMatchEpisode() {
        SignalIngestionRequest request = new SignalIngestionRequest(
                "disk-recovered-1", "episode-1", "another-project", SignalType.DISK_LOW,
                SignalStatus.RECOVERED, OBSERVED_AT, new BigDecimal("20"), new BigDecimal("15"),
                null, null, null);
        when(signals.findEvent(request.eventKey())).thenReturn(Optional.empty());
        when(signals.findEpisode(request.episodeKey())).thenReturn(Optional.of(activeEpisode()));

        assertThatThrownBy(() -> service.accept(request))
                .isInstanceOf(SignalIngestionConflictException.class);

        verify(signals, never()).recoverEpisode(any(), any());
    }

    @Test
    void should_notAccessEpisode_when_exactReplayAlreadyExists() {
        SignalIngestionRequest request = http("http-alert-1", SignalStatus.ALERT);
        String requestDigest = digest.calculate(request);
        when(signals.findEvent(request.eventKey())).thenReturn(Optional.of(
                new StoredSignalEvent(EPISODE_ID, requestDigest)));

        service.accept(request);

        verify(signals, never()).findEpisode(any());
    }

    private static StoredSignalEpisode activeEpisode() {
        return new StoredSignalEpisode(EPISODE_ID, "form-dock", SignalType.DISK_LOW,
                "ACTIVE", INCIDENT_ID, OBSERVED_AT.minusSeconds(60));
    }

    private static SignalIngestionRequest disk(String eventKey, SignalStatus status) {
        return new SignalIngestionRequest(eventKey, "episode-1", "form-dock", SignalType.DISK_LOW,
                status, OBSERVED_AT, new BigDecimal("14"), new BigDecimal("15"),
                null, null, null);
    }

    private static SignalIngestionRequest http(String eventKey, SignalStatus status) {
        return new SignalIngestionRequest(eventKey, "episode-http-1", "form-dock",
                SignalType.HTTP_5XX_BURST, status, OBSERVED_AT, null, null, 12, 300, 10);
    }
}
