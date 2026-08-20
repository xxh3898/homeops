package dev.homeops.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class NotificationOutboxStore {
    private static final String DISABLED_CODE = "NOTIFICATIONS_DISABLED";
    private static final String UNKNOWN_CODE = "LEASE_OUTCOME_UNKNOWN";
    private final JdbcTemplate jdbc;

    NotificationOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID insertOrFind(
            NotificationIntent intent,
            String canonicalHash,
            String payloadJson,
            NotificationStatus status,
            Instant now) {
        UUID id = UUID.randomUUID();
        boolean suppressed = status == NotificationStatus.SUPPRESSED;
        int inserted = jdbc.update("""
                INSERT INTO notification_event (
                    id, deduplication_key, canonical_deduplication_hash,
                    source_type, source_id, parent_notification_id, payload,
                    channel, severity, event_type, status, attempt_count,
                    occurred_at, next_attempt_at, failure_code,
                    created_at, updated_at, terminal_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb),
                        'DISCORD', ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (channel, canonical_deduplication_hash)
                    WHERE canonical_deduplication_hash IS NOT NULL
                    DO NOTHING
                """,
                id,
                "sha256:" + canonicalHash,
                canonicalHash,
                intent.sourceType().name(),
                intent.sourceId(),
                intent.parentNotificationId(),
                payloadJson,
                intent.severity().name(),
                intent.eventType(),
                status.name(),
                Timestamp.from(intent.occurredAt()),
                suppressed ? null : Timestamp.from(now),
                suppressed ? DISABLED_CODE : null,
                Timestamp.from(now),
                Timestamp.from(now),
                suppressed ? Timestamp.from(now) : null);
        if (inserted == 1) {
            return id;
        }
        return jdbc.queryForObject("""
                SELECT id
                FROM notification_event
                WHERE channel = 'DISCORD' AND canonical_deduplication_hash = ?
                """, UUID.class, canonicalHash);
    }

    Optional<NotificationEventReference> findEvent(
            NotificationSourceType sourceType,
            UUID sourceId,
            String eventType) {
        return jdbc.query("""
                SELECT id, status
                FROM notification_event
                WHERE channel = 'DISCORD'
                  AND canonical_deduplication_hash IS NOT NULL
                  AND source_type = ?
                  AND source_id = ?
                  AND event_type = ?
                """, (row, index) -> new NotificationEventReference(
                row.getObject("id", UUID.class),
                NotificationStatus.valueOf(row.getString("status"))),
                sourceType.name(), sourceId, eventType).stream().findFirst();
    }

    Optional<NotificationClaim> claimNext(
            Instant now,
            Instant leaseExpiresAt,
            UUID leaseToken,
            int maximumAttempts) {
        List<NotificationClaim> claims = jdbc.query("""
                WITH candidate AS (
                    SELECT id
                    FROM notification_event
                    WHERE canonical_deduplication_hash IS NOT NULL
                      AND attempt_count < ?
                      AND (
                          (status = 'PENDING' AND next_attempt_at <= ?)
                          OR
                          (status = 'DELIVERING' AND lease_expires_at <= ?)
                      )
                    ORDER BY COALESCE(next_attempt_at, lease_expires_at), occurred_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE notification_event event
                SET status = 'DELIVERING',
                    attempt_count = event.attempt_count + 1,
                    last_attempt_at = ?,
                    lease_token = ?,
                    lease_expires_at = ?,
                    next_attempt_at = NULL,
                    failure_code = NULL,
                    failure_summary = NULL,
                    terminal_at = NULL,
                    updated_at = ?
                FROM candidate
                WHERE event.id = candidate.id
                RETURNING event.id, event.lease_token, event.attempt_count,
                          event.severity, event.payload::text
                """,
                NotificationOutboxStore::mapClaim,
                maximumAttempts,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                leaseToken,
                Timestamp.from(leaseExpiresAt),
                Timestamp.from(now));
        return claims.stream().findFirst();
    }

    int markExpiredExhaustedLeasesUnknown(Instant now, int maximumAttempts, int batchSize) {
        return jdbc.update("""
                WITH expired AS (
                    SELECT id
                    FROM notification_event
                    WHERE canonical_deduplication_hash IS NOT NULL
                      AND status = 'DELIVERING'
                      AND lease_expires_at <= ?
                      AND attempt_count >= ?
                    ORDER BY lease_expires_at, occurred_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE notification_event event
                SET status = 'DELIVERY_UNKNOWN',
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    failure_code = ?,
                    failure_summary = 'Discord delivery outcome could not be confirmed',
                    terminal_at = ?,
                    updated_at = ?
                FROM expired
                WHERE event.id = expired.id
                """,
                Timestamp.from(now), maximumAttempts, batchSize, UNKNOWN_CODE,
                Timestamp.from(now), Timestamp.from(now));
    }

    int suppressDisabled(Instant now, int batchSize) {
        return jdbc.update("""
                WITH suppressible AS (
                    SELECT id
                    FROM notification_event
                    WHERE canonical_deduplication_hash IS NOT NULL
                      AND (
                          status = 'PENDING'
                          OR (status = 'DELIVERING' AND lease_expires_at <= ?)
                      )
                    ORDER BY occurred_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE notification_event event
                SET status = 'SUPPRESSED',
                    next_attempt_at = NULL,
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    failure_code = ?,
                    failure_summary = NULL,
                    terminal_at = ?,
                    updated_at = ?
                FROM suppressible
                WHERE event.id = suppressible.id
                """,
                Timestamp.from(now), batchSize, DISABLED_CODE,
                Timestamp.from(now), Timestamp.from(now));
    }

    boolean markSent(UUID id, UUID leaseToken, Instant now) {
        return updateTerminal(id, leaseToken, NotificationStatus.SENT,
                null, null, now, true) == 1;
    }

    boolean markFailed(UUID id, UUID leaseToken, String failureCode, Instant now) {
        return updateTerminal(id, leaseToken, NotificationStatus.FAILED,
                failureCode, "Discord delivery failed", now, false) == 1;
    }

    boolean markDeliveryUnknown(UUID id, UUID leaseToken, String failureCode, Instant now) {
        return updateTerminal(id, leaseToken, NotificationStatus.DELIVERY_UNKNOWN,
                failureCode, "Discord delivery outcome could not be confirmed", now, false) == 1;
    }

    boolean scheduleRetry(
            UUID id,
            UUID leaseToken,
            String failureCode,
            Instant nextAttemptAt,
            Instant now) {
        return jdbc.update("""
                UPDATE notification_event
                SET status = 'PENDING',
                    next_attempt_at = ?,
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    failure_code = ?,
                    failure_summary = 'Discord delivery will be retried',
                    terminal_at = NULL,
                    updated_at = ?
                WHERE id = ? AND status = 'DELIVERING' AND lease_token = ?
                """,
                Timestamp.from(nextAttemptAt), failureCode, Timestamp.from(now), id, leaseToken) == 1;
    }

    int deleteExpiredTerminalRows(Instant sentThreshold, Instant failedThreshold) {
        return jdbc.update("""
                DELETE FROM notification_event event
                WHERE event.canonical_deduplication_hash IS NOT NULL
                  AND (
                      (event.status IN ('SENT', 'SUPPRESSED') AND event.terminal_at < ?)
                      OR
                      (event.status IN ('FAILED', 'DELIVERY_UNKNOWN') AND event.terminal_at < ?)
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM notification_event child
                      WHERE child.parent_notification_id = event.id
                  )
                """, Timestamp.from(sentThreshold), Timestamp.from(failedThreshold));
    }

    private int updateTerminal(
            UUID id,
            UUID leaseToken,
            NotificationStatus status,
            String failureCode,
            String failureSummary,
            Instant now,
            boolean sent) {
        return jdbc.update("""
                UPDATE notification_event
                SET status = ?,
                    sent_at = ?,
                    next_attempt_at = NULL,
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    failure_code = ?,
                    failure_summary = ?,
                    terminal_at = ?,
                    updated_at = ?
                WHERE id = ? AND status = 'DELIVERING' AND lease_token = ?
                """,
                status.name(), sent ? Timestamp.from(now) : null,
                failureCode, failureSummary, Timestamp.from(now), Timestamp.from(now),
                id, leaseToken);
    }

    private static NotificationClaim mapClaim(ResultSet row, int index) throws SQLException {
        return new NotificationClaim(
                row.getObject("id", UUID.class),
                row.getObject("lease_token", UUID.class),
                row.getInt("attempt_count"),
                NotificationSeverity.valueOf(row.getString("severity")),
                row.getString("payload"));
    }
}
