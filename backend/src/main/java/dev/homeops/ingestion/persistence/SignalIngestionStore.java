package dev.homeops.ingestion.persistence;

import dev.homeops.common.PostgresqlTimestamp;
import dev.homeops.ingestion.api.SignalIngestionRequest;
import dev.homeops.ingestion.api.SignalIngestionRequest.SignalStatus;
import dev.homeops.ingestion.api.SignalIngestionRequest.SignalType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SignalIngestionStore {
    private final JdbcTemplate jdbc;

    public SignalIngestionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredSignalEvent> findEvent(String eventKey) {
        return jdbc.query("""
                SELECT event.episode_id, event.ingestion_digest
                FROM monitoring_signal_event event
                WHERE event.event_key = ?
                """, (row, index) -> new StoredSignalEvent(
                row.getObject("episode_id", UUID.class), row.getString("ingestion_digest")), eventKey)
                .stream()
                .findFirst();
    }

    public Optional<StoredSignalEpisode> findEpisode(String episodeKey) {
        return jdbc.query("""
                SELECT id, project, signal_type, status, incident_id, alerted_at
                FROM monitoring_signal_episode
                WHERE episode_key = ?
                """, (row, index) -> new StoredSignalEpisode(
                row.getObject("id", UUID.class), row.getString("project"),
                SignalType.valueOf(row.getString("signal_type")), row.getString("status"),
                row.getObject("incident_id", UUID.class), row.getTimestamp("alerted_at").toInstant()),
                episodeKey).stream().findFirst();
    }

    public Optional<UUID> insertAlertEpisode(SignalIngestionRequest request) {
        UUID incidentId = UUID.randomUUID();
        UUID episodeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO incident
                    (id, service_id, incident_type, severity, status, title, summary,
                     opened_at, last_observed_at)
                VALUES (?, NULL, ?, ?, 'OPEN', ?, ?, ?, ?)
                """, incidentId, incidentType(request.signalType()), severity(request.signalType()),
                title(request), summary(request.signalType()), timestamp(request.observedAt()),
                timestamp(request.observedAt()));

        Optional<UUID> inserted = jdbc.query("""
                INSERT INTO monitoring_signal_episode
                    (id, episode_key, project, signal_type, status, incident_id,
                     alerted_at, last_observed_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING id
                """, (row, index) -> row.getObject("id", UUID.class), episodeId, request.episodeKey(),
                request.project(), request.signalType().name(), incidentId, timestamp(request.observedAt()),
                timestamp(request.observedAt())).stream().findFirst();

        if (inserted.isEmpty() && jdbc.update("DELETE FROM incident WHERE id = ?", incidentId) != 1) {
            throw new IllegalStateException("Uncommitted signal incident cleanup failed");
        }
        return inserted;
    }

    public boolean insertEventIfAbsent(UUID episodeId, SignalIngestionRequest request, String digest) {
        return jdbc.update("""
                INSERT INTO monitoring_signal_event
                    (id, event_key, episode_id, project, signal_type, status, observed_at,
                     available_percent, threshold_percent, observed_count, window_seconds,
                     threshold_count, ingestion_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), request.eventKey(), episodeId, request.project(),
                request.signalType().name(), request.status().name(), timestamp(request.observedAt()),
                request.availablePercent(), request.thresholdPercent(), request.count(), request.windowSeconds(),
                request.thresholdCount(), digest) == 1;
    }

    public boolean recoverEpisode(StoredSignalEpisode episode, SignalIngestionRequest request) {
        int updated = jdbc.update("""
                UPDATE monitoring_signal_episode
                SET status = 'RECOVERED', recovered_at = ?, last_observed_at = ?
                WHERE id = ? AND status = 'ACTIVE' AND alerted_at <= ?
                """, timestamp(request.observedAt()), timestamp(request.observedAt()), episode.id(),
                timestamp(request.observedAt()));
        if (updated == 0) {
            return false;
        }
        if (jdbc.update("""
                UPDATE incident
                SET status = 'RESOLVED', resolved_at = ?, last_observed_at = ?,
                    resolved_xid = pg_current_xact_id()
                WHERE id = ? AND status IN ('OPEN', 'ACKNOWLEDGED')
                """, timestamp(request.observedAt()), timestamp(request.observedAt()), episode.incidentId()) != 1) {
            throw new IllegalStateException("Signal incident recovery failed");
        }
        return true;
    }

    private static String incidentType(SignalType type) {
        return "SIGNAL_" + type.name();
    }

    private static String severity(SignalType type) {
        return switch (type) {
            case DISK_LOW -> "CRITICAL";
            case HTTP_5XX_BURST -> "WARNING";
        };
    }

    private static String title(SignalIngestionRequest request) {
        return request.project() + switch (request.signalType()) {
            case DISK_LOW -> " disk availability is low";
            case HTTP_5XX_BURST -> " HTTP 5xx burst detected";
        };
    }

    private static String summary(SignalType type) {
        return switch (type) {
            case DISK_LOW -> "Available disk capacity crossed its configured threshold.";
            case HTTP_5XX_BURST -> "HTTP 5xx responses crossed the configured burst threshold.";
        };
    }

    private static Timestamp timestamp(Instant value) {
        return PostgresqlTimestamp.toTimestamp(value);
    }

    public record StoredSignalEvent(UUID episodeId, String digest) { }

    public record StoredSignalEpisode(
            UUID id,
            String project,
            SignalType signalType,
            String status,
            UUID incidentId,
            Instant alertedAt) {
        public boolean matches(SignalIngestionRequest request) {
            return project.equals(request.project()) && signalType == request.signalType();
        }
    }
}
