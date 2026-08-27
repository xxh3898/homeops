package dev.homeops.ingestion;

import dev.homeops.common.EventKeyConflictException;
import dev.homeops.common.InvalidIngestionStateTransitionException;
import dev.homeops.common.SignalIngestionConflictException;
import dev.homeops.ingestion.api.IngestionAcceptedResponse;
import dev.homeops.ingestion.api.SignalIngestionRequest;
import dev.homeops.ingestion.persistence.SignalIngestionStore;
import dev.homeops.ingestion.persistence.SignalIngestionStore.StoredSignalEpisode;
import dev.homeops.ingestion.persistence.SignalIngestionStore.StoredSignalEvent;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SignalIngestionService {
    private final SignalIngestionStore signals;
    private final IngestionDigest digest;

    public SignalIngestionService(SignalIngestionStore signals, IngestionDigest digest) {
        this.signals = signals;
        this.digest = digest;
    }

    @Transactional
    public IngestionAcceptedResponse accept(SignalIngestionRequest request) {
        SignalIngestionRequest canonicalRequest = IngestionTimestampCanonicalizer.canonicalize(request);
        String requestDigest = digest.calculate(canonicalRequest);
        Optional<StoredSignalEvent> existing = signals.findEvent(canonicalRequest.eventKey());
        if (existing.isPresent()) {
            return exactDuplicateOrConflict(canonicalRequest, requestDigest, existing.get());
        }
        return switch (canonicalRequest.status()) {
            case ALERT -> acceptAlert(canonicalRequest, requestDigest);
            case RECOVERED -> acceptRecovery(canonicalRequest, requestDigest);
        };
    }

    private IngestionAcceptedResponse acceptAlert(SignalIngestionRequest request, String requestDigest) {
        Optional<StoredSignalEpisode> existingEpisode = signals.findEpisode(request.episodeKey());
        if (existingEpisode.isPresent()) {
            rejectEpisodeMismatch(existingEpisode.get(), request);
            throw new InvalidIngestionStateTransitionException(existingEpisode.get().status(), request.status().name());
        }

        Optional<java.util.UUID> inserted = signals.insertAlertEpisode(request);
        if (inserted.isEmpty()) {
            Optional<StoredSignalEvent> winner = signals.findEvent(request.eventKey());
            if (winner.isPresent()) {
                return exactDuplicateOrConflict(request, requestDigest, winner.get());
            }
            throw new SignalIngestionConflictException();
        }
        if (!signals.insertEventIfAbsent(inserted.get(), request, requestDigest)) {
            return resolveEventInsertMiss(request, requestDigest);
        }
        return new IngestionAcceptedResponse(inserted.get(), false);
    }

    private IngestionAcceptedResponse acceptRecovery(SignalIngestionRequest request, String requestDigest) {
        StoredSignalEpisode episode = signals.findEpisode(request.episodeKey())
                .orElseThrow(SignalIngestionConflictException::new);
        rejectEpisodeMismatch(episode, request);
        if (!"ACTIVE".equals(episode.status())) {
            throw new InvalidIngestionStateTransitionException(episode.status(), request.status().name());
        }
        if (!signals.recoverEpisode(episode, request)) {
            Optional<StoredSignalEvent> winner = signals.findEvent(request.eventKey());
            if (winner.isPresent()) {
                return exactDuplicateOrConflict(request, requestDigest, winner.get());
            }
            StoredSignalEpisode current = signals.findEpisode(request.episodeKey())
                    .orElseThrow(SignalIngestionConflictException::new);
            throw new InvalidIngestionStateTransitionException(current.status(), request.status().name());
        }
        if (!signals.insertEventIfAbsent(episode.id(), request, requestDigest)) {
            return resolveEventInsertMiss(request, requestDigest);
        }
        return new IngestionAcceptedResponse(episode.id(), false);
    }

    private IngestionAcceptedResponse resolveEventInsertMiss(
            SignalIngestionRequest request, String requestDigest) {
        StoredSignalEvent stored = signals.findEvent(request.eventKey())
                .orElseThrow(SignalIngestionConflictException::new);
        return exactDuplicateOrConflict(request, requestDigest, stored);
    }

    private static IngestionAcceptedResponse exactDuplicateOrConflict(
            SignalIngestionRequest request, String requestDigest, StoredSignalEvent stored) {
        if (!requestDigest.equals(stored.digest())) {
            throw new EventKeyConflictException(request.eventKey());
        }
        return new IngestionAcceptedResponse(stored.episodeId(), true);
    }

    private static void rejectEpisodeMismatch(StoredSignalEpisode episode, SignalIngestionRequest request) {
        if (!episode.matches(request)) {
            throw new SignalIngestionConflictException();
        }
    }
}
