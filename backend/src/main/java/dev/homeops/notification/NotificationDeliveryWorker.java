package dev.homeops.notification;

import dev.homeops.notification.config.HomeOpsNotificationProperties;
import dev.homeops.notification.discord.DiscordDeliveryDisposition;
import dev.homeops.notification.discord.DiscordDeliveryResult;
import dev.homeops.notification.discord.DiscordNotificationClient;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryWorker.class);

    private final NotificationOutboxTransactions transactions;
    private final NotificationPayloadCodec payloadCodec;
    private final DiscordNotificationClient discord;
    private final NotificationBackoffPolicy backoff;
    private final HomeOpsNotificationProperties properties;
    private final Executor executor;
    private final Clock clock;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    @Autowired
    NotificationDeliveryWorker(
            NotificationOutboxTransactions transactions,
            NotificationPayloadCodec payloadCodec,
            DiscordNotificationClient discord,
            NotificationBackoffPolicy backoff,
            HomeOpsNotificationProperties properties,
            @Qualifier("notificationDeliveryExecutor") Executor executor) {
        this(transactions, payloadCodec, discord, backoff, properties, executor, Clock.systemUTC());
    }

    NotificationDeliveryWorker(
            NotificationOutboxTransactions transactions,
            NotificationPayloadCodec payloadCodec,
            DiscordNotificationClient discord,
            NotificationBackoffPolicy backoff,
            HomeOpsNotificationProperties properties,
            Executor executor,
            Clock clock) {
        this.transactions = transactions;
        this.payloadCodec = payloadCodec;
        this.discord = discord;
        this.backoff = backoff;
        this.properties = properties;
        this.executor = executor;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${homeops.notifications.poll-delay:1s}")
    public void poll() {
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    drainOnce();
                } finally {
                    inFlight.set(false);
                }
            });
        } catch (RejectedExecutionException exception) {
            inFlight.set(false);
            LOGGER.warn("Notification delivery was deferred because its bounded executor is busy");
        }
    }

    void drainOnce() {
        if (!properties.enabled()) {
            transactions.suppressDisabledBatch();
            return;
        }
        for (int index = 0; index < properties.batchSize(); index++) {
            Optional<NotificationClaim> claim = transactions.claimNext();
            if (claim.isEmpty()) {
                return;
            }
            deliver(claim.orElseThrow());
        }
    }

    private void deliver(NotificationClaim claim) {
        NotificationPayload payload;
        try {
            payload = payloadCodec.decode(claim.payloadJson());
        } catch (IllegalArgumentException exception) {
            transactions.markFailed(claim, "INVALID_STORED_PAYLOAD");
            return;
        }

        DiscordDeliveryResult result;
        try {
            result = discord.send(payload, claim.severity());
        } catch (RuntimeException exception) {
            transactions.markDeliveryUnknown(claim, "UNEXPECTED_DELIVERY_FAILURE");
            LOGGER.warn("Notification delivery ended with an unexpected bounded failure");
            return;
        }
        applyResult(claim, result);
    }

    private void applyResult(NotificationClaim claim, DiscordDeliveryResult result) {
        if (result.disposition() == DiscordDeliveryDisposition.SUCCESS) {
            transactions.markSent(claim);
            return;
        }
        if (result.disposition() == DiscordDeliveryDisposition.UNKNOWN) {
            transactions.markDeliveryUnknown(claim, result.failureCode());
            return;
        }
        if (result.disposition() == DiscordDeliveryDisposition.TERMINAL) {
            transactions.markFailed(claim, result.failureCode());
            return;
        }
        if (claim.attemptCount() >= properties.maxAttempts()) {
            transactions.markFailed(claim, "MAX_ATTEMPTS_EXCEEDED");
            return;
        }
        if (result.serverRetryAfter()
                .filter(delay -> delay.compareTo(properties.maxBackoff()) > 0)
                .isPresent()) {
            transactions.markFailed(claim, "RETRY_DELAY_OUT_OF_RANGE");
            return;
        }
        Instant nextAttempt = clock.instant().plus(backoff.delay(
                claim.attemptCount(), result.serverRetryAfter()));
        transactions.scheduleRetry(claim, result.failureCode(), nextAttempt);
    }
}
