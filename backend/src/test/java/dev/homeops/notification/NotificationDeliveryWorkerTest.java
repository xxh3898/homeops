package dev.homeops.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import dev.homeops.notification.config.HomeOpsNotificationProperties;
import dev.homeops.notification.discord.DiscordDeliveryResult;
import dev.homeops.notification.discord.DiscordNotificationClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryWorkerTest {
    private static final Instant NOW = Instant.parse("2026-08-19T03:00:00Z");
    private static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/"
            + "x".repeat(64);

    @Mock private NotificationOutboxTransactions transactions;
    @Mock private NotificationPayloadCodec codec;
    @Mock private DiscordNotificationClient discord;

    @Test
    void should_suppressWithoutOutbound_when_notificationsAreDisabled() {
        NotificationDeliveryWorker worker = worker(properties(false), Runnable::run, () -> 0.0);

        worker.drainOnce();

        verify(transactions).suppressDisabledBatch();
        verifyNoInteractions(codec, discord);
    }

    @Test
    void should_markSent_when_discordConfirmsDelivery() {
        NotificationClaim claim = claim(1);
        NotificationPayload payload = payload();
        when(transactions.claimNext()).thenReturn(Optional.of(claim), Optional.empty());
        when(codec.decode(claim.payloadJson())).thenReturn(payload);
        when(discord.send(payload, claim.severity())).thenReturn(DiscordDeliveryResult.success());

        worker(properties(true), Runnable::run, () -> 0.0).drainOnce();

        verify(transactions).markSent(claim);
        verify(transactions, never()).scheduleRetry(any(), any(), any());
    }

    @Test
    void should_scheduleRetryUsingServerDelay_when_retryableFailureIsKnown() {
        NotificationClaim claim = claim(1);
        NotificationPayload payload = payload();
        when(transactions.claimNext()).thenReturn(Optional.of(claim), Optional.empty());
        when(codec.decode(claim.payloadJson())).thenReturn(payload);
        when(discord.send(payload, claim.severity()))
                .thenReturn(DiscordDeliveryResult.retryable("HTTP_429", Duration.ofSeconds(20)));

        worker(properties(true), Runnable::run, () -> 0.0).drainOnce();

        verify(transactions).scheduleRetry(
                eq(claim), eq("HTTP_429"), eq(NOW.plusSeconds(20)));
    }

    @Test
    void should_notRetry_when_deliveryOutcomeIsUnknown() {
        NotificationClaim claim = claim(1);
        NotificationPayload payload = payload();
        when(transactions.claimNext()).thenReturn(Optional.of(claim), Optional.empty());
        when(codec.decode(claim.payloadJson())).thenReturn(payload);
        when(discord.send(payload, claim.severity()))
                .thenReturn(DiscordDeliveryResult.unknown("REQUEST_TIMEOUT"));

        worker(properties(true), Runnable::run, () -> 0.0).drainOnce();

        verify(transactions).markDeliveryUnknown(claim, "REQUEST_TIMEOUT");
        verify(transactions, never()).scheduleRetry(any(), any(), any());
    }

    @Test
    void should_markFailedWithoutRetry_when_failureIsTerminalOrAttemptsAreExhausted() {
        NotificationClaim terminal = claim(1);
        NotificationPayload payload = payload();
        when(transactions.claimNext()).thenReturn(Optional.of(terminal), Optional.empty());
        when(codec.decode(terminal.payloadJson())).thenReturn(payload);
        when(discord.send(payload, terminal.severity()))
                .thenReturn(DiscordDeliveryResult.terminal("HTTP_4XX"));

        worker(properties(true), Runnable::run, () -> 0.0).drainOnce();

        verify(transactions).markFailed(terminal, "HTTP_4XX");

        NotificationClaim exhausted = claim(6);
        org.mockito.Mockito.reset(transactions, codec, discord);
        when(transactions.claimNext()).thenReturn(Optional.of(exhausted), Optional.empty());
        when(codec.decode(exhausted.payloadJson())).thenReturn(payload);
        when(discord.send(payload, exhausted.severity()))
                .thenReturn(DiscordDeliveryResult.retryable("HTTP_5XX", null));

        worker(properties(true), Runnable::run, () -> 0.0).drainOnce();

        verify(transactions).markFailed(exhausted, "MAX_ATTEMPTS_EXCEEDED");
        verify(transactions, never()).scheduleRetry(any(), any(), any());
    }

    @Test
    void should_failClosedWithoutOutbound_when_storedPayloadIsInvalid() {
        NotificationClaim claim = claim(1);
        when(transactions.claimNext()).thenReturn(Optional.of(claim), Optional.empty());
        when(codec.decode(claim.payloadJson())).thenThrow(new IllegalArgumentException("invalid"));

        worker(properties(true), Runnable::run, () -> 0.0).drainOnce();

        verify(transactions).markFailed(claim, "INVALID_STORED_PAYLOAD");
        verifyNoInteractions(discord);
    }

    @Test
    void should_keepOnlyOneDrainInFlight_when_schedulerTicksOverlap() {
        List<Runnable> submitted = new ArrayList<>();
        Executor deferred = submitted::add;
        when(transactions.claimNext()).thenReturn(Optional.empty());
        NotificationDeliveryWorker worker = worker(properties(true), deferred, () -> 0.0);

        worker.poll();
        worker.poll();

        org.assertj.core.api.Assertions.assertThat(submitted).hasSize(1);
        submitted.getFirst().run();
        worker.poll();
        org.assertj.core.api.Assertions.assertThat(submitted).hasSize(2);
    }

    @Test
    void should_allowNextTick_when_boundedExecutorRejectsSubmission() {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        Executor rejecting = runnable -> {
            attempts.incrementAndGet();
            throw new RejectedExecutionException("bounded");
        };
        NotificationDeliveryWorker worker = worker(properties(true), rejecting, () -> 0.0);

        worker.poll();
        worker.poll();

        org.assertj.core.api.Assertions.assertThat(attempts).hasValue(2);
    }

    @Test
    void should_claimAtMostConfiguredBatchSize_when_dueRowsRemainAvailable() {
        NotificationClaim claim = claim(1);
        NotificationPayload payload = payload();
        when(transactions.claimNext()).thenReturn(Optional.of(claim));
        when(codec.decode(claim.payloadJson())).thenReturn(payload);
        when(discord.send(payload, claim.severity())).thenReturn(DiscordDeliveryResult.success());

        worker(properties(true), Runnable::run, () -> 0.0).drainOnce();

        verify(transactions, times(10)).claimNext();
        verify(discord, times(10)).send(payload, claim.severity());
    }

    @Test
    void should_redactStoredPayload_when_claimIsRenderedForDiagnostics() {
        NotificationClaim claim = new NotificationClaim(
                UUID.randomUUID(), UUID.randomUUID(), 1,
                NotificationSeverity.WARNING, "synthetic-sensitive-payload");

        org.assertj.core.api.Assertions.assertThat(claim.toString())
                .doesNotContain("synthetic-sensitive-payload")
                .contains("payload=redacted");
    }

    private NotificationDeliveryWorker worker(
            HomeOpsNotificationProperties properties,
            Executor executor,
            java.util.function.DoubleSupplier jitter) {
        return new NotificationDeliveryWorker(
                transactions,
                codec,
                discord,
                new NotificationBackoffPolicy(
                        properties.initialBackoff(), properties.maxBackoff(), jitter),
                properties,
                executor,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static NotificationClaim claim(int attempt) {
        return new NotificationClaim(
                UUID.randomUUID(), UUID.randomUUID(), attempt,
                NotificationSeverity.WARNING, "{\"bounded\":true}");
    }

    private static NotificationPayload payload() {
        return new NotificationPayload(
                "BACKUP_FAILED", "Backup failed", "A backup failed.",
                List.of(), NOW);
    }

    private static HomeOpsNotificationProperties properties(boolean enabled) {
        return new HomeOpsNotificationProperties(
                enabled, enabled ? WEBHOOK : null,
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofSeconds(1), 10, 6,
                Duration.ofSeconds(5), Duration.ofMinutes(15),
                65_536, 8_192, Duration.ofDays(30), Duration.ofDays(90));
    }
}
