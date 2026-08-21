package dev.homeops.agent.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class ContainerActionAuditPostgresqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-20T15:00:00Z");
    private static final String PRINCIPAL = "admin@example.test";
    private static final String CONTAINER_ID = "0123456789ab";
    private static final String IDEMPOTENCY_KEY = "20000000-0000-4000-8000-000000000001";

    private static PostgresqlContainerActionTestDatabase database;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactionManager;
    private static ContainerActionAuditStore store;

    private ContainerActionAuditTransactions audit;

    @BeforeAll
    static void migrateSchema() {
        database = PostgresqlContainerActionTestDatabase.create();
        database.migrateToCurrent();
        jdbc = database.jdbc();
        transactionManager = new DataSourceTransactionManager(database.dataSource());
        store = new ContainerActionAuditStore(jdbc);
    }

    @AfterAll
    static void dropSchema() {
        database.close();
    }

    @BeforeEach
    void clearRows() {
        jdbc.update("DELETE FROM container_action_audit");
        audit = transactionsAt(NOW);
    }

    @Test
    void should_commitReservationBeforeBrokerEnqueueAndPersistOnlyAllowlistedAuditFields() {
        ContainerActionRateLimiter rateLimiter = mock(ContainerActionRateLimiter.class);
        ContainerControlBroker broker = mock(ContainerControlBroker.class);
        CompletableFuture<ContainerControlResult> result = new CompletableFuture<>();
        when(rateLimiter.tryAcquire(PRINCIPAL, IDEMPOTENCY_KEY)).thenReturn(true);
        when(broker.enqueue(CONTAINER_ID, ContainerControlOperation.RESTART))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM container_action_audit",
                            Integer.class)).isOne();
                    return new ContainerControlRequestTicket(
                            UUID.randomUUID(), NOW.plusSeconds(15), result);
                });
        ContainerActionService service = service(audit, rateLimiter, broker);

        ContainerActionService.Submission submission = service.submit(
                CONTAINER_ID,
                ContainerControlOperation.RESTART,
                "RESTART:" + CONTAINER_ID,
                ContainerActionIdempotencyKey.parse(IDEMPOTENCY_KEY),
                PRINCIPAL);

        assertThat(submission.created()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM container_action_audit
                WHERE id = ?
                  AND idempotency_key = ?
                  AND principal = ?
                  AND action = 'RESTART'
                  AND container_id_prefix = ?
                  AND result = 'REQUESTED'
                  AND container_name IS NULL
                  AND image IS NULL
                  AND failure_summary IS NULL
                  AND metadata = '{}'::jsonb
                """, Integer.class,
                submission.record().operationId(),
                IDEMPOTENCY_KEY,
                PRINCIPAL,
                CONTAINER_ID)).isOne();
    }

    @RepeatedTest(5)
    void should_createOneRowAndDispatchOneWinner_when_sameKeyRequestsRace() throws Exception {
        ContainerActionRateLimiter rateLimiter = new ContainerActionRateLimiter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        ContainerControlBroker broker = mock(ContainerControlBroker.class);
        when(broker.enqueue(CONTAINER_ID, ContainerControlOperation.START))
                .thenReturn(new ContainerControlRequestTicket(
                        UUID.randomUUID(),
                        NOW.plusSeconds(15),
                        new CompletableFuture<>()));
        BarrierAuditTransactions barrierAudit = barrierTransactionsAt(NOW);
        ContainerActionService service = service(barrierAudit, rateLimiter, broker);

        List<ContainerActionService.Submission> submissions;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<ContainerActionService.Submission>> futures = List.of(
                    executor.submit(() -> submit(service, IDEMPOTENCY_KEY)),
                    executor.submit(() -> submit(service, IDEMPOTENCY_KEY)));
            boolean concurrentLookupReached = barrierAudit.awaitConcurrentLookup();
            barrierAudit.releaseReservations();
            assertThat(concurrentLookupReached).isTrue();
            submissions = futures.stream().map(this::futureSubmission).toList();
        }

        assertThat(submissions)
                .extracting(submission -> submission.record().operationId())
                .containsOnly(submissions.getFirst().record().operationId());
        assertThat(submissions.stream().filter(ContainerActionService.Submission::created))
                .hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM container_action_audit",
                Integer.class)).isOne();
        verify(broker, times(1)).enqueue(CONTAINER_ID, ContainerControlOperation.START);
        for (int key = 2; key <= ContainerActionRateLimiter.MAXIMUM_REQUESTS; key++) {
            assertThat(submit(service, idempotencyKey(key)).created()).isTrue();
        }
        assertThatThrownBy(() -> submit(service, idempotencyKey(6)))
                .isInstanceOf(ContainerActionException.class)
                .extracting("status")
                .isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM container_action_audit",
                Integer.class)).isEqualTo(ContainerActionRateLimiter.MAXIMUM_REQUESTS);
        verify(broker, times(ContainerActionRateLimiter.MAXIMUM_REQUESTS))
                .enqueue(CONTAINER_ID, ContainerControlOperation.START);
    }

    @Test
    void should_notPersistSixthOrLaterDistinctKey_when_rateLimitIsExceeded() {
        ContainerActionRateLimiter rateLimiter = new ContainerActionRateLimiter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        ContainerControlBroker broker = mock(ContainerControlBroker.class);
        when(broker.enqueue(CONTAINER_ID, ContainerControlOperation.START))
                .thenAnswer(invocation -> new ContainerControlRequestTicket(
                        UUID.randomUUID(),
                        NOW.plusSeconds(15),
                        new CompletableFuture<>()));
        ContainerActionService service = service(audit, rateLimiter, broker);

        for (int key = 1; key <= ContainerActionRateLimiter.MAXIMUM_REQUESTS; key++) {
            assertThat(submit(service, idempotencyKey(key)).created()).isTrue();
        }
        for (int key = 6; key <= 15; key++) {
            String rejectedKey = idempotencyKey(key);
            assertThatThrownBy(() -> submit(service, rejectedKey))
                    .isInstanceOf(ContainerActionException.class)
                    .extracting("status")
                    .isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM container_action_audit",
                Integer.class)).isEqualTo(ContainerActionRateLimiter.MAXIMUM_REQUESTS);
        verify(broker, times(ContainerActionRateLimiter.MAXIMUM_REQUESTS))
                .enqueue(CONTAINER_ID, ContainerControlOperation.START);
    }

    @Test
    void should_allowTerminalReplayAfterFiveDistinctKeys_withoutRateOrAuditGrowth() {
        ContainerActionRateLimiter rateLimiter = new ContainerActionRateLimiter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        ContainerControlBroker broker = mock(ContainerControlBroker.class);
        when(broker.enqueue(CONTAINER_ID, ContainerControlOperation.START))
                .thenAnswer(invocation -> new ContainerControlRequestTicket(
                        UUID.randomUUID(),
                        NOW.plusSeconds(15),
                        new CompletableFuture<>()));
        ContainerActionService service = service(audit, rateLimiter, broker);

        List<ContainerActionService.Submission> created = java.util.stream.IntStream.rangeClosed(
                        1, ContainerActionRateLimiter.MAXIMUM_REQUESTS)
                .mapToObj(key -> submit(service, idempotencyKey(key)))
                .toList();
        ContainerActionAuditRecord first = created.getFirst().record();
        assertThat(audit.complete(
                first.operationId(),
                ContainerActionStatus.APPLIED,
                "APPLIED",
                NOW.plusSeconds(1))).isTrue();

        ContainerActionService.Submission replay = submit(service, idempotencyKey(1));

        assertThat(replay.created()).isFalse();
        assertThat(replay.record().operationId()).isEqualTo(first.operationId());
        assertThat(replay.record().status()).isEqualTo(ContainerActionStatus.APPLIED);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM container_action_audit",
                Integer.class)).isEqualTo(ContainerActionRateLimiter.MAXIMUM_REQUESTS);
        assertThatThrownBy(() -> submit(service, idempotencyKey(6)))
                .isInstanceOf(ContainerActionException.class)
                .extracting("status")
                .isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
        verify(broker, times(ContainerActionRateLimiter.MAXIMUM_REQUESTS))
                .enqueue(CONTAINER_ID, ContainerControlOperation.START);
    }

    @Test
    void should_rejectExistingPayloadConflict_withoutRateAuditOrDispatchGrowth() {
        ContainerActionReservation existing = audit.reserve(
                IDEMPOTENCY_KEY,
                PRINCIPAL,
                CONTAINER_ID,
                ContainerControlOperation.START);
        ContainerActionRateLimiter rateLimiter = new ContainerActionRateLimiter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        ContainerControlBroker broker = mock(ContainerControlBroker.class);
        ContainerActionService service = service(audit, rateLimiter, broker);

        assertThatThrownBy(() -> service.submit(
                CONTAINER_ID,
                ContainerControlOperation.STOP,
                "STOP:" + CONTAINER_ID,
                ContainerActionIdempotencyKey.parse(IDEMPOTENCY_KEY),
                PRINCIPAL))
                .isInstanceOf(ContainerActionException.class)
                .extracting("status")
                .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);

        assertThat(rateLimiter.principalCount()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM container_action_audit",
                Integer.class)).isOne();
        assertThat(audit.find(existing.record().operationId()).orElseThrow())
                .isEqualTo(existing.record());
        verifyNoInteractions(broker);
    }

    @Test
    void should_allowExactlyOneTerminalWinner_when_resultAndReconciliationRace() throws Exception {
        ContainerActionReservation reservation = audit.reserve(
                IDEMPOTENCY_KEY,
                PRINCIPAL,
                CONTAINER_ID,
                ContainerControlOperation.START);
        Instant later = NOW.plus(ContainerActionAuditTransactions.STALE_REQUEST_AFTER).plusSeconds(1);
        ContainerActionAuditTransactions laterAudit = transactionsAt(later);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Integer> updates;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = List.of(
                    executor.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                        return laterAudit.complete(
                                reservation.record().operationId(),
                                ContainerActionStatus.APPLIED,
                                "APPLIED",
                                later) ? 1 : 0;
                    }),
                    executor.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                        return laterAudit.reconcileStaleRequested();
                    }));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            updates = futures.stream().map(this::futureInteger).toList();
        }

        assertThat(updates).containsExactlyInAnyOrder(0, 1);
        ContainerActionAuditRecord terminal = laterAudit.find(reservation.record().operationId())
                .orElseThrow();
        assertThat(terminal.status()).isIn(
                ContainerActionStatus.APPLIED,
                ContainerActionStatus.OUTCOME_UNKNOWN);
        assertThat(laterAudit.complete(
                terminal.operationId(),
                ContainerActionStatus.FAILED,
                "CONTROL_RESULT_UNAVAILABLE",
                later.plusSeconds(1))).isFalse();
    }

    @Test
    void should_reconcileOnlyAtFixedCutoffWithoutRequeue() {
        ContainerActionReservation reservation = audit.reserve(
                IDEMPOTENCY_KEY,
                PRINCIPAL,
                CONTAINER_ID,
                ContainerControlOperation.RESTART);

        assertThat(transactionsAt(NOW.plus(
                ContainerActionAuditTransactions.STALE_REQUEST_AFTER).minusNanos(1_000))
                .reconcileStaleRequested()).isZero();
        assertThat(transactionsAt(NOW.plus(
                ContainerActionAuditTransactions.STALE_REQUEST_AFTER))
                .reconcileStaleRequested()).isOne();

        ContainerActionAuditRecord reconciled = audit.find(reservation.record().operationId())
                .orElseThrow();
        assertThat(reconciled.status()).isEqualTo(ContainerActionStatus.OUTCOME_UNKNOWN);
        assertThat(reconciled.reasonCode()).isEqualTo("RESULT_UNAVAILABLE");
        assertThat(reconciled.completedAt()).isEqualTo(
                NOW.plus(ContainerActionAuditTransactions.STALE_REQUEST_AFTER));
    }

    private ContainerActionService.Submission submit(
            ContainerActionService service,
            String idempotencyKey) {
        return service.submit(
                CONTAINER_ID,
                ContainerControlOperation.START,
                "START:" + CONTAINER_ID,
                ContainerActionIdempotencyKey.parse(idempotencyKey),
                PRINCIPAL);
    }

    private static String idempotencyKey(int suffix) {
        return "20000000-0000-4000-8000-" + String.format("%012d", suffix);
    }

    private ContainerActionService.Submission futureSubmission(
            Future<ContainerActionService.Submission> result) {
        try {
            return result.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent container action did not complete", exception);
        }
    }

    private Integer futureInteger(Future<Integer> result) {
        try {
            return result.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent terminal update did not complete", exception);
        }
    }

    private ContainerActionAuditTransactions transactionsAt(Instant instant) {
        return new ContainerActionAuditTransactions(
                store,
                transactionManager,
                Clock.fixed(instant, ZoneOffset.UTC),
                UUID::randomUUID);
    }

    private BarrierAuditTransactions barrierTransactionsAt(Instant instant) {
        return new BarrierAuditTransactions(
                store,
                transactionManager,
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static ContainerActionService service(
            ContainerActionAuditTransactions audit,
            ContainerActionRateLimiter rateLimiter,
            ContainerControlBroker broker) {
        return new ContainerActionService(
                audit,
                rateLimiter,
                broker,
                Runnable::run,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class BarrierAuditTransactions extends ContainerActionAuditTransactions {
        private final CountDownLatch concurrentLookup = new CountDownLatch(2);
        private final CountDownLatch reservationRelease = new CountDownLatch(1);

        private BarrierAuditTransactions(
                ContainerActionAuditStore store,
                DataSourceTransactionManager transactionManager,
                Clock clock) {
            super(store, transactionManager, clock, UUID::randomUUID);
        }

        @Override
        Optional<ContainerActionAuditRecord> findByIdempotencyKey(String idempotencyKey) {
            Optional<ContainerActionAuditRecord> existing = super.findByIdempotencyKey(idempotencyKey);
            if (existing.isEmpty() && IDEMPOTENCY_KEY.equals(idempotencyKey)) {
                concurrentLookup.countDown();
                try {
                    if (!reservationRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new AssertionError("Concurrent audit lookup release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Concurrent audit lookup was interrupted", exception);
                }
            }
            return existing;
        }

        private boolean awaitConcurrentLookup() throws InterruptedException {
            return concurrentLookup.await(5, java.util.concurrent.TimeUnit.SECONDS);
        }

        private void releaseReservations() {
            reservationRelease.countDown();
        }
    }
}
