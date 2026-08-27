package dev.homeops.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homeops.activity.ActivityStore;
import dev.homeops.activity.ActivityTypeFilter;
import dev.homeops.activity.api.ActivityEventResponse;
import dev.homeops.common.EventKeyConflictException;
import dev.homeops.common.InvalidIngestionStateTransitionException;
import dev.homeops.common.SignalIngestionConflictException;
import dev.homeops.ingestion.IngestionDigest;
import dev.homeops.ingestion.SignalIngestionService;
import dev.homeops.ingestion.api.IngestionAcceptedResponse;
import dev.homeops.ingestion.api.SignalIngestionRequest;
import dev.homeops.ingestion.api.SignalIngestionRequest.SignalStatus;
import dev.homeops.ingestion.api.SignalIngestionRequest.SignalType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class SignalIngestionPostgresqlIntegrationTest {
    private static final Instant ALERTED_AT = Instant.parse("2026-08-27T01:02:03.123456499Z");
    private static final Instant RECOVERED_AT = Instant.parse("2026-08-27T01:07:03.999999500Z");

    private static PostgresqlIngestionTestDatabase database;
    private static org.springframework.jdbc.core.JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static SignalIngestionStore store;
    private static SignalIngestionService service;

    @BeforeAll
    static void createSchema() {
        database = PostgresqlIngestionTestDatabase.create();
        database.migrateToCurrent();
        jdbc = database.jdbc();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        store = new SignalIngestionStore(jdbc);
        service = new SignalIngestionService(store, new IngestionDigest());
    }

    @AfterAll
    static void dropSchema() {
        database.close();
    }

    @BeforeEach
    void clearSignalTables() {
        jdbc.update("DELETE FROM monitoring_signal_event");
        jdbc.update("DELETE FROM monitoring_signal_episode");
        jdbc.update("DELETE FROM incident");
        jdbc.update("DELETE FROM ingestion_event_key_ledger WHERE source_type = 'SIGNAL'");
    }

    @Test
    void should_createAndRecoverSameIncident_when_signalEpisodeTransitions() {
        IngestionAcceptedResponse alerted = inTransaction(() -> service.accept(disk(
                "disk-alert-1", "disk-episode-1", SignalStatus.ALERT, ALERTED_AT, "14", "15")));

        assertThat(signalCount("monitoring_signal_episode")).isOne();
        assertThat(signalCount("monitoring_signal_event")).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT status, incident_id, alerted_at, recovered_at
                FROM monitoring_signal_episode WHERE id = ?
                """, alerted.id()))
                .containsEntry("status", "ACTIVE")
                .containsEntry("recovered_at", null);
        UUID incidentId = jdbc.queryForObject(
                "SELECT incident_id FROM monitoring_signal_episode WHERE id = ?", UUID.class, alerted.id());
        assertThat(jdbc.queryForObject("SELECT status FROM incident WHERE id = ?", String.class, incidentId))
                .isEqualTo("OPEN");

        IngestionAcceptedResponse recovered = inTransaction(() -> service.accept(disk(
                "disk-recovered-1", "disk-episode-1", SignalStatus.RECOVERED,
                RECOVERED_AT, "20", "15")));
        IngestionAcceptedResponse recoveryReplay = inTransaction(() -> service.accept(disk(
                "disk-recovered-1", "disk-episode-1", SignalStatus.RECOVERED,
                RECOVERED_AT, "20.00", "15.0")));

        assertThat(recovered).isEqualTo(new IngestionAcceptedResponse(alerted.id(), false));
        assertThat(recoveryReplay).isEqualTo(new IngestionAcceptedResponse(alerted.id(), true));
        assertThat(signalCount("monitoring_signal_episode")).isOne();
        assertThat(signalCount("monitoring_signal_event")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM incident WHERE id = ?", String.class, incidentId))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM monitoring_signal_episode WHERE id = ?", String.class, alerted.id()))
                .isEqualTo("RECOVERED");

        ActivityStore activities = new ActivityStore(jdbc);
        List<ActivityEventResponse> projected = activities.find(
                        null, activities.currentVisibilitySnapshot(), ActivityTypeFilter.INCIDENT, 10)
                .stream().map(ActivityStore.StoredActivity::response).toList();
        assertThat(projected).extracting(ActivityEventResponse::status)
                .containsExactly("RESOLVED", "OPEN");
        assertThat(projected).allSatisfy(event -> {
            assertThat(event.id()).isEqualTo(incidentId.toString());
            assertThat(event.type()).isEqualTo(ActivityEventResponse.Type.INCIDENT);
            assertThat(event.context()).isEqualTo("SIGNAL_DISK_LOW");
            assertThat(event.title()).isEqualTo("form-dock disk availability is low");
            assertThat(event.toString()).doesNotContain("disk-alert-1", "disk-episode-1");
        });
    }

    @Test
    void should_acceptExactCanonicalReplayWithoutCreatingRows_when_eventIsRepeated() {
        SignalIngestionRequest first = disk(
                "disk-alert-replay", "disk-episode-replay", SignalStatus.ALERT,
                ALERTED_AT, "14.0", "15.00");
        IngestionAcceptedResponse accepted = inTransaction(() -> service.accept(first));
        SignalIngestionRequest canonicalEquivalent = disk(
                first.eventKey(), first.episodeKey(), SignalStatus.ALERT,
                Instant.parse("2026-08-27T01:02:03.123456Z"), "14", "15");

        IngestionAcceptedResponse replay = inTransaction(() -> service.accept(canonicalEquivalent));

        assertThat(replay).isEqualTo(new IngestionAcceptedResponse(accepted.id(), true));
        assertThat(signalCount("monitoring_signal_episode")).isOne();
        assertThat(signalCount("monitoring_signal_event")).isOne();
        assertThat(signalCount("incident")).isOne();
    }

    @Test
    void should_rejectConflictsWithoutCreatingSecondIncident_when_lifecycleIsInvalid() {
        SignalIngestionRequest alert = http(
                "http-alert-1", "http-episode-1", "form-dock", SignalStatus.ALERT, ALERTED_AT, 12);
        inTransaction(() -> service.accept(alert));

        assertThatThrownBy(() -> inTransaction(() -> service.accept(http(
                alert.eventKey(), alert.episodeKey(), "form-dock", SignalStatus.ALERT, ALERTED_AT, 13))))
                .isInstanceOf(EventKeyConflictException.class);
        assertThatThrownBy(() -> inTransaction(() -> service.accept(http(
                "http-alert-2", alert.episodeKey(), "form-dock", SignalStatus.ALERT, ALERTED_AT, 12))))
                .isInstanceOf(InvalidIngestionStateTransitionException.class);
        assertThatThrownBy(() -> inTransaction(() -> service.accept(http(
                "http-recovery-mismatch", alert.episodeKey(), "another-project",
                SignalStatus.RECOVERED, RECOVERED_AT, 0))))
                .isInstanceOf(SignalIngestionConflictException.class);
        assertThatThrownBy(() -> inTransaction(() -> service.accept(disk(
                "http-recovery-type-mismatch", alert.episodeKey(), SignalStatus.RECOVERED,
                RECOVERED_AT, "20", "15"))))
                .isInstanceOf(SignalIngestionConflictException.class);
        assertThatThrownBy(() -> inTransaction(() -> service.accept(http(
                "http-recovery-missing", "missing-episode", "form-dock",
                SignalStatus.RECOVERED, RECOVERED_AT, 0))))
                .isInstanceOf(SignalIngestionConflictException.class);

        assertThat(signalCount("monitoring_signal_episode")).isOne();
        assertThat(signalCount("monitoring_signal_event")).isOne();
        assertThat(signalCount("incident")).isOne();
    }

    @Test
    void should_convergeToOneWinner_when_sameAlertIsSubmittedConcurrently() throws Exception {
        SignalIngestionRequest request = disk(
                "disk-alert-race", "disk-episode-race", SignalStatus.ALERT,
                ALERTED_AT, "14", "15");

        List<IngestionAcceptedResponse> responses = runRace(request, request);

        assertThat(responses).extracting(IngestionAcceptedResponse::id).containsOnly(responses.getFirst().id());
        assertThat(responses).extracting(IngestionAcceptedResponse::duplicate)
                .containsExactlyInAnyOrder(false, true);
        assertThat(signalCount("monitoring_signal_episode")).isOne();
        assertThat(signalCount("monitoring_signal_event")).isOne();
        assertThat(signalCount("incident")).isOne();
    }

    @Test
    void should_allowOnlyOneActiveProjectType_when_differentEpisodesRace() throws Exception {
        SignalIngestionRequest first = disk(
                "disk-active-race-1", "disk-active-episode-1", SignalStatus.ALERT,
                ALERTED_AT, "14", "15");
        SignalIngestionRequest second = disk(
                "disk-active-race-2", "disk-active-episode-2", SignalStatus.ALERT,
                ALERTED_AT.plusSeconds(1), "13", "15");

        List<String> results = runOutcomeRace(first, second);

        assertThat(results).containsExactlyInAnyOrder("accepted", "conflict");
        assertThat(signalCount("monitoring_signal_episode")).isOne();
        assertThat(signalCount("monitoring_signal_event")).isOne();
        assertThat(signalCount("incident")).isOne();
    }

    @Test
    void should_rollbackIncidentAndEpisode_when_eventPersistenceFails() {
        SignalIngestionRequest request = disk(
                "disk-rollback-1", "disk-rollback-episode", SignalStatus.ALERT,
                ALERTED_AT, "14", "15");

        assertThatThrownBy(() -> inTransaction(() -> {
            UUID episodeId = store.insertAlertEpisode(request).orElseThrow();
            store.insertEventIfAbsent(episodeId, request, "invalid-digest");
            return episodeId;
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(signalCount("monitoring_signal_episode")).isZero();
        assertThat(signalCount("monitoring_signal_event")).isZero();
        assertThat(signalCount("incident")).isZero();
    }

    @Test
    void should_rejectReopenAndOlderRecovery_when_episodeIsTerminalOrTimestampRegresses() {
        inTransaction(() -> service.accept(disk(
                "disk-alert-terminal", "disk-episode-terminal", SignalStatus.ALERT,
                ALERTED_AT, "14", "15")));
        assertThatThrownBy(() -> inTransaction(() -> service.accept(disk(
                "disk-recovery-old", "disk-episode-terminal", SignalStatus.RECOVERED,
                ALERTED_AT.minusSeconds(1), "20", "15"))))
                .isInstanceOf(InvalidIngestionStateTransitionException.class);
        inTransaction(() -> service.accept(disk(
                "disk-recovery-terminal", "disk-episode-terminal", SignalStatus.RECOVERED,
                RECOVERED_AT, "20", "15")));

        assertThatThrownBy(() -> inTransaction(() -> service.accept(disk(
                "disk-alert-reopen", "disk-episode-terminal", SignalStatus.ALERT,
                RECOVERED_AT.plusSeconds(1), "14", "15"))))
                .isInstanceOf(InvalidIngestionStateTransitionException.class);

        assertThat(signalCount("monitoring_signal_episode")).isOne();
        assertThat(signalCount("monitoring_signal_event")).isEqualTo(2);
        assertThat(signalCount("incident")).isOne();
    }

    @Test
    void should_rejectReplayWithoutResurrection_when_signalHistoryWasDeleted() {
        SignalIngestionRequest request = disk(
                "disk-deleted-history", "disk-deleted-episode", SignalStatus.ALERT,
                ALERTED_AT, "14", "15");
        IngestionAcceptedResponse accepted = inTransaction(() -> service.accept(request));
        UUID incidentId = jdbc.queryForObject(
                "SELECT incident_id FROM monitoring_signal_episode WHERE id = ?", UUID.class, accepted.id());
        jdbc.update("DELETE FROM monitoring_signal_event WHERE episode_id = ?", accepted.id());
        jdbc.update("DELETE FROM monitoring_signal_episode WHERE id = ?", accepted.id());
        jdbc.update("DELETE FROM incident WHERE id = ?", incidentId);

        assertThatThrownBy(() -> inTransaction(() -> service.accept(request)))
                .isInstanceOf(SignalIngestionConflictException.class);

        assertThat(signalCount("monitoring_signal_event")).isZero();
        assertThat(signalCount("monitoring_signal_episode")).isZero();
        assertThat(signalCount("incident")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ingestion_event_key_ledger
                WHERE source_type = 'SIGNAL' AND event_key = ?
                """, Integer.class, request.eventKey())).isOne();
    }

    private static List<IngestionAcceptedResponse> runRace(
            SignalIngestionRequest first, SignalIngestionRequest second) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<IngestionAcceptedResponse>> futures = List.of(
                    executor.submit(() -> acceptWhenReleased(first, ready, start)),
                    executor.submit(() -> acceptWhenReleased(second, ready, start)));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return futures.stream().map(SignalIngestionPostgresqlIntegrationTest::resultOf).toList();
        }
    }

    private static List<String> runOutcomeRace(
            SignalIngestionRequest first, SignalIngestionRequest second) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = List.of(
                    executor.submit(() -> outcomeWhenReleased(first, ready, start)),
                    executor.submit(() -> outcomeWhenReleased(second, ready, start)));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return futures.stream().map(SignalIngestionPostgresqlIntegrationTest::resultOf).toList();
        }
    }

    private static IngestionAcceptedResponse acceptWhenReleased(
            SignalIngestionRequest request, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return inTransaction(() -> service.accept(request));
    }

    private static String outcomeWhenReleased(
            SignalIngestionRequest request, CountDownLatch ready, CountDownLatch start) throws Exception {
        try {
            acceptWhenReleased(request, ready, start);
            return "accepted";
        } catch (SignalIngestionConflictException exception) {
            return "conflict";
        }
    }

    private static <T> T resultOf(Future<T> future) {
        try {
            return future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent signal ingestion did not complete", exception);
        }
    }

    private static <T> T inTransaction(java.util.function.Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    private static int signalCount(String table) {
        if (!List.of("monitoring_signal_episode", "monitoring_signal_event", "incident").contains(table)) {
            throw new IllegalArgumentException("Unsupported table");
        }
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static SignalIngestionRequest disk(
            String eventKey,
            String episodeKey,
            SignalStatus status,
            Instant observedAt,
            String availablePercent,
            String thresholdPercent) {
        return new SignalIngestionRequest(eventKey, episodeKey, "form-dock", SignalType.DISK_LOW,
                status, observedAt, new BigDecimal(availablePercent), new BigDecimal(thresholdPercent),
                null, null, null);
    }

    private static SignalIngestionRequest http(
            String eventKey,
            String episodeKey,
            String project,
            SignalStatus status,
            Instant observedAt,
            int count) {
        return new SignalIngestionRequest(eventKey, episodeKey, project, SignalType.HTTP_5XX_BURST,
                status, observedAt, null, null, count, 300, 10);
    }
}
