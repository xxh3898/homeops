package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.homeops.notification.config.HomeOpsNotificationProperties;
import dev.homeops.notification.discord.DiscordDeliveryResult;
import dev.homeops.notification.discord.DiscordNotificationClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class NotificationOutboxPostgresqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-19T02:00:00Z");
    private static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/"
            + "x".repeat(64);

    private static PostgresqlNotificationTestDatabase database;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactionManager;
    private static NotificationOutboxStore store;
    private static NotificationPayloadCodec codec;

    @BeforeAll
    static void migrateAndCreateStore() {
        database = PostgresqlNotificationTestDatabase.create();
        database.migrateToCurrent();
        jdbc = database.jdbc();
        transactionManager = new DataSourceTransactionManager(database.dataSource());
        store = new NotificationOutboxStore(jdbc);
        codec = new NotificationPayloadCodec(new ObjectMapper(), 8_192);
    }

    @AfterAll
    static void dropSchema() {
        database.close();
    }

    @BeforeEach
    void clearRows() {
        jdbc.update("DELETE FROM notification_event");
    }

    @Test
    void should_insertOnePendingIntentAndHideRawDedupMaterial_when_notificationsAreEnabled() {
        NotificationOutboxTransactions transactions = transactionsAt(NOW, true);
        NotificationIntent intent = intent("sensitive-logical-name", null);

        UUID first = transactions.enqueue(intent);
        UUID duplicate = transactions.enqueue(intent);

        assertThat(duplicate).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_event", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM notification_event WHERE id = ?", String.class, first))
                .isEqualTo("PENDING");
        String storedKey = jdbc.queryForObject(
                "SELECT deduplication_key FROM notification_event WHERE id = ?", String.class, first);
        assertThat(storedKey).startsWith("sha256:").doesNotContain("sensitive-logical-name");
        assertThat(jdbc.queryForObject("""
                SELECT octet_length(payload::text) <= 8192
                FROM notification_event WHERE id = ?
                """, Boolean.class, first)).isTrue();
    }

    @Test
    void should_insertSuppressedIntentWithoutReplay_when_notificationsAreDisabled() {
        NotificationOutboxTransactions disabled = transactionsAt(NOW, false);
        UUID id = disabled.enqueue(intent("disabled", null));

        assertThat(status(id)).isEqualTo("SUPPRESSED");
        assertThat(jdbc.queryForObject(
                "SELECT next_attempt_at IS NULL FROM notification_event WHERE id = ?",
                Boolean.class, id)).isTrue();
        assertThat(transactionsAt(NOW.plusSeconds(1), true).claimNext()).isEmpty();
    }

    @Test
    void should_rollBackIntent_when_enclosingDomainTransactionRollsBack() {
        NotificationOutboxTransactions transactions = transactionsAt(NOW, true);
        TransactionTemplate domainTransaction = new TransactionTemplate(transactionManager);

        domainTransaction.executeWithoutResult(status -> {
            transactions.enqueue(intent("rolled-back-domain", null));
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_event", Integer.class)).isZero();
    }

    @Test
    void should_allowOneConcurrentClaimWinner_when_twoWorkersRace() throws Exception {
        transactionsAt(NOW, true).enqueue(intent("concurrent", null));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Optional<NotificationClaim>>> futures = List.of(
                    executor.submit(() -> claimWhenReleased(ready, start)),
                    executor.submit(() -> claimWhenReleased(ready, start)));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Optional<NotificationClaim>> results = futures.stream().map(this::future).toList();
            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            NotificationClaim winner = results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            assertThat(winner.attemptCount()).isEqualTo(1);
            assertThat(status(winner.id())).isEqualTo("DELIVERING");
        }
    }

    @Test
    void should_reclaimOnlyExpiredLeaseAndRejectOldLeaseCompletion() {
        NotificationOutboxTransactions original = transactionsAt(NOW, true);
        original.enqueue(intent("lease", null));
        NotificationClaim first = original.claimNext().orElseThrow();

        assertThat(transactionsAt(NOW.plusSeconds(29), true).claimNext()).isEmpty();

        NotificationOutboxTransactions afterExpiry = transactionsAt(NOW.plusSeconds(31), true);
        NotificationClaim second = afterExpiry.claimNext().orElseThrow();

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(original.markSent(first)).isFalse();
        assertThat(afterExpiry.markSent(second)).isTrue();
        assertThat(status(second.id())).isEqualTo("SENT");
    }

    @Test
    void should_waitUntilPersistedRetryIsDue_afterWorkerRestart() {
        NotificationOutboxTransactions firstProcess = transactionsAt(NOW, true);
        UUID id = firstProcess.enqueue(intent("restart", null));
        NotificationClaim first = firstProcess.claimNext().orElseThrow();
        assertThat(firstProcess.scheduleRetry(first, "HTTP_5XX", NOW.plusSeconds(10))).isTrue();

        NotificationOutboxTransactions restartedBeforeDue = freshTransactionsAt(NOW.plusSeconds(9), true);
        assertThat(restartedBeforeDue.claimNext()).isEmpty();

        NotificationClaim second = freshTransactionsAt(NOW.plusSeconds(10), true)
                .claimNext().orElseThrow();
        assertThat(second.id()).isEqualTo(id);
        assertThat(second.attemptCount()).isEqualTo(2);
    }

    @Test
    void should_terminalizeExpiredLeaseAsUnknown_when_attemptBudgetIsExhausted() {
        NotificationOutboxTransactions transactions = transactionsAt(NOW, true);
        UUID id = transactions.enqueue(intent("exhausted", null));
        transactions.claimNext().orElseThrow();
        jdbc.update("""
                UPDATE notification_event
                SET attempt_count = 6, lease_expires_at = ?
                WHERE id = ?
                """, java.sql.Timestamp.from(NOW.minusSeconds(1)), id);

        assertThat(transactionsAt(NOW, true).claimNext()).isEmpty();
        assertThat(status(id)).isEqualTo("DELIVERY_UNKNOWN");
        assertThat(jdbc.queryForObject(
                "SELECT failure_code FROM notification_event WHERE id = ?", String.class, id))
                .isEqualTo("LEASE_OUTCOME_UNKNOWN");
    }

    @Test
    void should_commitClaimBeforeHttpAndReleaseDatabaseLockDuringDelivery() {
        HomeOpsNotificationProperties properties = properties(true);
        NotificationOutboxTransactions transactions = transactionsAt(NOW, properties);
        UUID id = transactions.enqueue(intent("tx-boundary", null));
        DiscordNotificationClient discord = mock(DiscordNotificationClient.class);
        when(discord.send(any(), any())).thenAnswer(invocation -> {
            assertThat(status(id)).isEqualTo("DELIVERING");
            assertThat(jdbc.update(
                    "UPDATE notification_event SET updated_at = updated_at WHERE id = ?", id))
                    .isEqualTo(1);
            return DiscordDeliveryResult.success();
        });
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(
                transactions, codec, discord,
                new NotificationBackoffPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15), () -> 0.0),
                properties, Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC));

        worker.drainOnce();

        assertThat(status(id)).isEqualTo("SENT");
    }

    @Test
    void should_suppressPendingAndExpiredDeliveryWithoutOutbound_when_killSwitchIsFalse() {
        NotificationOutboxTransactions enabled = transactionsAt(NOW, true);
        UUID pending = enabled.enqueue(intent("pending-disable", null));
        UUID delivering = enabled.enqueue(intent("delivering-disable", null));
        NotificationClaim inFlight = enabled.claimNext().orElseThrow();
        assertThat(inFlight.id()).isIn(pending, delivering);

        HomeOpsNotificationProperties disabledProperties = properties(false);
        NotificationOutboxTransactions disabled = transactionsAt(NOW.plusSeconds(31), disabledProperties);
        DiscordNotificationClient discord = mock(DiscordNotificationClient.class);
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(
                disabled, codec, discord,
                new NotificationBackoffPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15), () -> 0.0),
                disabledProperties, Runnable::run, Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC));

        worker.drainOnce();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_event WHERE status = 'SUPPRESSED'", Integer.class))
                .isEqualTo(2);
        verify(discord, never()).send(any(), any());
        assertThat(transactionsAt(NOW.plusSeconds(32), true).claimNext()).isEmpty();
    }

    @Test
    void should_applyTerminalRetentionWithoutDeletingActiveOrReferencedParentRows() {
        NotificationOutboxTransactions transactions = transactionsAt(NOW, true);
        UUID independentSent = transactions.enqueue(intent("old-sent", null));
        UUID oldFailed = transactions.enqueue(intent("old-failed", null));
        UUID oldUnknown = transactions.enqueue(intent("old-unknown", null));
        UUID active = transactions.enqueue(intent("active", null));
        UUID parent = transactions.enqueue(intent("parent", null));
        UUID child = transactions.enqueue(intent("child", parent));
        makeTerminal(independentSent, "SENT", NOW.minus(Duration.ofDays(31)));
        makeTerminal(oldFailed, "FAILED", NOW.minus(Duration.ofDays(91)));
        makeTerminal(oldUnknown, "DELIVERY_UNKNOWN", NOW.minus(Duration.ofDays(91)));
        makeTerminal(parent, "SENT", NOW.minus(Duration.ofDays(31)));
        makeTerminal(child, "SENT", NOW.minus(Duration.ofDays(1)));

        int deleted = transactions.deleteExpiredTerminalRows();

        assertThat(deleted).isEqualTo(3);
        assertThat(existingIds()).containsExactlyInAnyOrder(active, parent, child);
    }

    private Optional<NotificationClaim> claimWhenReleased(
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return freshTransactionsAt(NOW, true).claimNext();
    }

    private Optional<NotificationClaim> future(Future<Optional<NotificationClaim>> future) {
        try {
            return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent notification claim did not complete", exception);
        }
    }

    private static NotificationOutboxTransactions transactionsAt(Instant now, boolean enabled) {
        return transactionsAt(now, properties(enabled));
    }

    private static NotificationOutboxTransactions transactionsAt(
            Instant now,
            HomeOpsNotificationProperties properties) {
        return new NotificationOutboxTransactions(
                store, codec, properties, transactionManager, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static NotificationOutboxTransactions freshTransactionsAt(Instant now, boolean enabled) {
        return new NotificationOutboxTransactions(
                new NotificationOutboxStore(new JdbcTemplate(database.dataSource())),
                new NotificationPayloadCodec(new ObjectMapper(), 8_192),
                properties(enabled),
                new DataSourceTransactionManager(database.dataSource()),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static HomeOpsNotificationProperties properties(boolean enabled) {
        return new HomeOpsNotificationProperties(
                enabled, enabled ? WEBHOOK : null,
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofSeconds(1), 10, 6,
                Duration.ofSeconds(5), Duration.ofMinutes(15),
                65_536, 8_192, Duration.ofDays(30), Duration.ofDays(90));
    }

    private static NotificationIntent intent(String deduplicationMaterial, UUID parentId) {
        return new NotificationIntent(
                NotificationSourceType.DEPLOYMENT,
                UUID.nameUUIDFromBytes(deduplicationMaterial.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                NotificationSeverity.WARNING,
                "DEPLOYMENT_FAILED",
                deduplicationMaterial,
                parentId,
                NOW,
                new NotificationPayload(
                        "DEPLOYMENT_FAILED",
                        "Deployment failed",
                        "A deployment reached a failed terminal state.",
                        List.of(new NotificationField("Environment", "production", true)),
                        NOW));
    }

    private String status(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM notification_event WHERE id = ?", String.class, id);
    }

    private void makeTerminal(UUID id, String status, Instant terminalAt) {
        jdbc.update("""
                UPDATE notification_event
                SET status = ?,
                    sent_at = ?,
                    next_attempt_at = NULL,
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    terminal_at = ?,
                    updated_at = ?
                WHERE id = ?
                """, status, status.equals("SENT") ? java.sql.Timestamp.from(terminalAt) : null,
                java.sql.Timestamp.from(terminalAt), java.sql.Timestamp.from(terminalAt), id);
    }

    private List<UUID> existingIds() {
        return jdbc.query("SELECT id FROM notification_event ORDER BY id",
                (row, index) -> row.getObject(1, UUID.class));
    }
}
