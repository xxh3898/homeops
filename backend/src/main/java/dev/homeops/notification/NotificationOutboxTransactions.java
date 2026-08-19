package dev.homeops.notification;

import dev.homeops.notification.config.HomeOpsNotificationProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class NotificationOutboxTransactions {
    private final NotificationOutboxStore store;
    private final NotificationPayloadCodec payloadCodec;
    private final HomeOpsNotificationProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    @Autowired
    NotificationOutboxTransactions(
            NotificationOutboxStore store,
            NotificationPayloadCodec payloadCodec,
            HomeOpsNotificationProperties properties,
            PlatformTransactionManager transactionManager) {
        this(store, payloadCodec, properties, transactionManager, Clock.systemUTC());
    }

    NotificationOutboxTransactions(
            NotificationOutboxStore store,
            NotificationPayloadCodec payloadCodec,
            HomeOpsNotificationProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.store = store;
        this.payloadCodec = payloadCodec;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    UUID enqueue(NotificationIntent intent) {
        String payload = payloadCodec.encode(intent.payload());
        String canonicalHash = canonicalHash(intent);
        Instant now = clock.instant();
        NotificationStatus status = properties.enabled()
                ? NotificationStatus.PENDING : NotificationStatus.SUPPRESSED;
        return required(transactions.execute(transaction ->
                store.insertOrFind(intent, canonicalHash, payload, status, now)));
    }

    Optional<NotificationEventReference> findEvent(
            NotificationSourceType sourceType,
            UUID sourceId,
            String eventType) {
        return required(transactions.execute(transaction ->
                store.findEvent(sourceType, sourceId, eventType)));
    }

    Optional<NotificationClaim> claimNext() {
        Instant now = clock.instant();
        UUID leaseToken = UUID.randomUUID();
        return required(transactions.execute(transaction -> {
            store.markExpiredExhaustedLeasesUnknown(
                    now, properties.maxAttempts(), properties.batchSize());
            return store.claimNext(
                    now,
                    now.plus(properties.leaseDuration()),
                    leaseToken,
                    properties.maxAttempts());
        }));
    }

    int suppressDisabledBatch() {
        Instant now = clock.instant();
        return required(transactions.execute(transaction ->
                store.suppressDisabled(now, properties.batchSize())));
    }

    boolean markSent(NotificationClaim claim) {
        Instant now = clock.instant();
        return Boolean.TRUE.equals(transactions.execute(transaction ->
                store.markSent(claim.id(), claim.leaseToken(), now)));
    }

    boolean markFailed(NotificationClaim claim, String failureCode) {
        Instant now = clock.instant();
        return Boolean.TRUE.equals(transactions.execute(transaction ->
                store.markFailed(claim.id(), claim.leaseToken(), failureCode, now)));
    }

    boolean markDeliveryUnknown(NotificationClaim claim, String failureCode) {
        Instant now = clock.instant();
        return Boolean.TRUE.equals(transactions.execute(transaction ->
                store.markDeliveryUnknown(claim.id(), claim.leaseToken(), failureCode, now)));
    }

    boolean scheduleRetry(NotificationClaim claim, String failureCode, Instant nextAttemptAt) {
        Instant now = clock.instant();
        return Boolean.TRUE.equals(transactions.execute(transaction ->
                store.scheduleRetry(claim.id(), claim.leaseToken(), failureCode, nextAttemptAt, now)));
    }

    int deleteExpiredTerminalRows() {
        Instant now = clock.instant();
        return required(transactions.execute(transaction -> store.deleteExpiredTerminalRows(
                now.minus(properties.sentRetention()),
                now.minus(properties.failedRetention()))));
    }

    private static String canonicalHash(NotificationIntent intent) {
        String canonical = component("DISCORD")
                + component(intent.sourceType().name())
                + component(intent.sourceId().toString())
                + component(intent.eventType())
                + component(intent.deduplicationMaterial());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available");
        }
    }

    private static String component(String value) {
        return value.length() + ":" + value;
    }

    private static <T> T required(T value) {
        if (value == null) {
            throw new IllegalStateException("Notification transaction returned no result");
        }
        return value;
    }
}
