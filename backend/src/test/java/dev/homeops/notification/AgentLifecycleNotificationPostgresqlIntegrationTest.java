package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.homeops.agent.AgentFreshnessNotificationMonitor;
import dev.homeops.agent.AgentSnapshotService;
import dev.homeops.agent.api.AgentSnapshotRequest;
import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.agent.persistence.AgentActivityStore;
import dev.homeops.agent.persistence.AgentStatusRepository;
import dev.homeops.agent.persistence.AgentStatusStore;
import dev.homeops.agent.persistence.HostMetricAggregateEntity;
import dev.homeops.agent.persistence.HostMetricAggregateRepository;
import dev.homeops.agent.persistence.ProcessedAgentSnapshotStore;
import dev.homeops.notification.config.HomeOpsNotificationProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class AgentLifecycleNotificationPostgresqlIntegrationTest {
    private static final String AGENT_ID = "local-mac";
    private static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/"
            + "x".repeat(64);

    private static PostgresqlNotificationTestDatabase database;
    private static JdbcTemplate jdbc;
    private static JpaTransactionManager transactionManager;
    private static TransactionTemplate transactions;
    private static EntityManagerFactory entityManagerFactory;
    private static HostMetricAggregateRepository metricRepository;
    private static AgentStatusRepository statusRepository;

    private AgentStatusStore statusStore;
    private ProcessedAgentSnapshotStore processedSnapshots;
    private AgentActivityStore activities;

    @BeforeAll
    static void migrateAndCreateJpaRepositories() {
        database = PostgresqlNotificationTestDatabase.create();
        database.migrateToCurrent();
        jdbc = database.jdbc();

        LocalContainerEntityManagerFactoryBean factory =
                new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(database.dataSource());
        factory.setPackagesToScan("dev.homeops.agent.persistence");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.jdbc.time_zone", "UTC"));
        factory.afterPropertiesSet();
        entityManagerFactory = factory.getObject();

        transactionManager = new JpaTransactionManager(entityManagerFactory);
        transactionManager.setDataSource(database.dataSource());
        transactionManager.afterPropertiesSet();
        transactions = new TransactionTemplate(transactionManager);

        EntityManager shared = SharedEntityManagerCreator
                .createSharedEntityManager(entityManagerFactory);
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(shared);
        metricRepository = repositories.getRepository(HostMetricAggregateRepository.class);
        statusRepository = repositories.getRepository(AgentStatusRepository.class);
    }

    @AfterAll
    static void dropSchema() {
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
        database.close();
    }

    @BeforeEach
    void clearRows() {
        jdbc.update("DELETE FROM notification_event");
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM host_metric_aggregate");
        jdbc.update("DELETE FROM processed_agent_snapshot");
        jdbc.update("DELETE FROM agent_status");
        statusStore = new AgentStatusStore(jdbc);
        processedSnapshots = new ProcessedAgentSnapshotStore(jdbc);
        activities = new AgentActivityStore(jdbc);
    }

    @Test
    void should_createOneSuppressedStaleRoot_when_concurrentFreshnessChecksRace() throws Exception {
        Instant now = Instant.now();
        UUID staleSnapshotId = UUID.randomUUID();
        seedStatus(staleSnapshotId, "v1", now.minusSeconds(40), now.minusSeconds(39));
        AgentFreshnessNotificationMonitor first = monitor(false, statusStore);
        AgentFreshnessNotificationMonitor second = monitor(false, statusStore);

        race(first::checkFreshness, second::checkFreshness);
        inTransaction(() -> {
            first.checkFreshness();
            second.checkFreshness();
            return null;
        });

        assertThat(notificationCount("AGENT_STALE")).isEqualTo(1);
        NotificationRow root = notification("AGENT_STALE");
        assertThat(root.sourceId()).isEqualTo(staleSnapshotId);
        assertThat(root.status()).isEqualTo("SUPPRESSED");
        assertThat(root.parentId()).isNull();
    }

    @Test
    void should_notCreateOldStaleRoot_when_freshSnapshotWinnerCommitsBeforeBlockedMonitor()
            throws Exception {
        Instant now = Instant.now();
        seedStatus(UUID.randomUUID(), "v1", now.minusSeconds(40), now.minusSeconds(39));
        CountDownLatch snapshotHasStatusLock = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        HostMetricAggregateRepository blockingMetricRepository = mock(HostMetricAggregateRepository.class);
        when(blockingMetricRepository.findByAgentIdAndBucketStart(any(), any()))
                .thenAnswer(invocation -> {
                    snapshotHasStatusLock.countDown();
                    assertThat(releaseSnapshot.await(5, TimeUnit.SECONDS)).isTrue();
                    return java.util.Optional.empty();
                });
        when(blockingMetricRepository.save(any(HostMetricAggregateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AgentSnapshotService service = service(true, 8_192, blockingMetricRepository);
        CountDownLatch monitorEnteredLockRead = new CountDownLatch(1);
        SignallingAgentStatusStore monitorStore =
                new SignallingAgentStatusStore(jdbc, monitorEnteredLockRead);
        AgentFreshnessNotificationMonitor monitor = monitor(true, monitorStore);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> snapshot = executor.submit(() -> {
                inTransaction(() -> service.accept(snapshot("v1", Instant.now().minusMillis(100))));
                return null;
            });
            assertThat(snapshotHasStatusLock.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Void> checker = executor.submit(() -> {
                inTransaction(() -> {
                    monitor.checkFreshness();
                    return null;
                });
                return null;
            });
            assertThat(monitorEnteredLockRead.await(5, TimeUnit.SECONDS)).isTrue();

            releaseSnapshot.countDown();
            await(snapshot);
            await(checker);
        }

        assertThat(notificationCount("AGENT_STALE")).isZero();
        assertThat(statusCapturedAt()).isAfter(now.minusSeconds(1));
    }

    @Test
    void should_atomicallyPersistVersionActivityMetricAndSuppressedIntent_when_versionChanges() {
        Instant now = Instant.now();
        seedStatus(UUID.randomUUID(), "v1", now.minusSeconds(2), now.minusSeconds(1));
        AgentSnapshotRequest request = snapshot("v2", Instant.now().minusMillis(100));

        inTransaction(() -> service(false, 8_192, metricRepository).accept(request));

        assertThat(statusVersion()).isEqualTo("v2");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM processed_agent_snapshot WHERE snapshot_id = ?",
                Integer.class,
                request.snapshotId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM host_metric_aggregate", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_event WHERE event_type = 'VERSION_CHANGED'",
                Integer.class)).isEqualTo(1);
        NotificationRow version = notification("AGENT_VERSION_CHANGED");
        assertThat(version.sourceId()).isEqualTo(request.snapshotId());
        assertThat(version.status()).isEqualTo("SUPPRESSED");
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM notification_event WHERE event_type = 'AGENT_VERSION_CHANGED'",
                String.class);
        assertThat(payload)
                .contains(AGENT_ID, "v2", "VERSION_CHANGED")
                .doesNotContain(
                        "cpuUsagePercent",
                        "memoryTotalBytes",
                        "diskTotalBytes",
                        "containers",
                        "tailnet",
                        "certificate",
                        "credential");
    }

    @Test
    void should_rollBackProcessedStatusMetricActivityAndOutbox_when_versionIntentEncodingFails() {
        Instant now = Instant.now();
        UUID priorSnapshotId = UUID.randomUUID();
        seedStatus(priorSnapshotId, "v1", now.minusSeconds(2), now.minusSeconds(1));
        AgentSnapshotRequest request = snapshot("v2", Instant.now().minusMillis(100));

        AgentSnapshotService service = service(false, 1, metricRepository);

        assertThatThrownBy(() -> inTransaction(() -> service.accept(request)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification payload exceeds the serialized byte limit");

        assertThat(statusVersion()).isEqualTo("v1");
        assertThat(jdbc.queryForObject(
                "SELECT last_snapshot_id FROM agent_status WHERE agent_id = ?",
                UUID.class,
                AGENT_ID)).isEqualTo(priorSnapshotId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM processed_agent_snapshot", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM host_metric_aggregate", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_event", Integer.class)).isZero();
        assertThat(service.latest()).isEmpty();
    }

    @Test
    void should_rollBackFreshSnapshotWrites_when_recoveryIntentEncodingFails() {
        Instant now = Instant.now();
        UUID staleSnapshotId = UUID.randomUUID();
        seedStatus(staleSnapshotId, "v1", now.minusSeconds(40), now.minusSeconds(39));
        inTransaction(() -> {
            monitor(true, statusStore).checkFreshness();
            return null;
        });
        UUID rootId = markRootSent();
        AgentSnapshotRequest request = snapshot("v1", Instant.now().minusMillis(100));
        AgentSnapshotService service = service(true, 1, metricRepository);

        assertThatThrownBy(() -> inTransaction(() -> service.accept(request)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification payload exceeds the serialized byte limit");

        assertThat(jdbc.queryForObject(
                "SELECT last_snapshot_id FROM agent_status WHERE agent_id = ?",
                UUID.class,
                AGENT_ID)).isEqualTo(staleSnapshotId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM processed_agent_snapshot", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM host_metric_aggregate", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_event", Integer.class)).isZero();
        assertThat(notificationEventTypes()).containsExactly("AGENT_STALE");
        assertThat(notification("AGENT_STALE").id()).isEqualTo(rootId);
        assertThat(notificationCount("AGENT_RECOVERED")).isZero();
        assertThat(service.latest()).isEmpty();
    }

    @Test
    void should_createRecoveryWithSentRootParent_when_freshSnapshotWins() {
        Instant now = Instant.now();
        UUID staleSnapshotId = UUID.randomUUID();
        seedStatus(staleSnapshotId, "v1", now.minusSeconds(40), now.minusSeconds(39));
        inTransaction(() -> {
            monitor(true, statusStore).checkFreshness();
            return null;
        });
        UUID rootId = markRootSent();

        inTransaction(() -> service(true, 8_192, metricRepository)
                .accept(snapshot("v1", Instant.now().minusMillis(100))));

        NotificationRow recovery = notification("AGENT_RECOVERED");
        assertThat(recovery.sourceId()).isEqualTo(staleSnapshotId);
        assertThat(recovery.parentId()).isEqualTo(rootId);
        assertThat(recovery.status()).isEqualTo("PENDING");
    }

    @Test
    void should_notReplaySuppressedStaleEpisode_when_notificationsAreEnabledLater() {
        Instant now = Instant.now();
        UUID staleSnapshotId = UUID.randomUUID();
        seedStatus(staleSnapshotId, "v1", now.minusSeconds(40), now.minusSeconds(39));
        inTransaction(() -> {
            monitor(false, statusStore).checkFreshness();
            return null;
        });

        inTransaction(() -> service(true, 8_192, metricRepository)
                .accept(snapshot("v1", Instant.now().minusMillis(100))));

        assertThat(notificationEventTypes()).containsExactly("AGENT_STALE");
        assertThat(notification("AGENT_STALE").status()).isEqualTo("SUPPRESSED");
        assertThat(notificationCount("AGENT_RECOVERED")).isZero();
    }

    private AgentFreshnessNotificationMonitor monitor(
            boolean enabled,
            AgentStatusStore store) {
        return new AgentFreshnessNotificationMonitor(
                agentProperties(), store, producer(enabled, 8_192));
    }

    private AgentSnapshotService service(
            boolean enabled,
            int payloadMaximumBytes,
            HostMetricAggregateRepository repository) {
        return new AgentSnapshotService(
                agentProperties(),
                statusRepository,
                statusStore,
                repository,
                processedSnapshots,
                activities,
                producer(enabled, payloadMaximumBytes));
    }

    private AgentLifecycleNotificationProducer producer(
            boolean enabled,
            int payloadMaximumBytes) {
        HomeOpsNotificationProperties properties = notificationProperties(
                enabled, payloadMaximumBytes);
        NotificationPayloadCodec codec = new NotificationPayloadCodec(
                new ObjectMapper(), properties);
        NotificationOutboxTransactions outboxTransactions =
                new NotificationOutboxTransactions(
                        new NotificationOutboxStore(jdbc),
                        codec,
                        properties,
                        transactionManager);
        return new AgentLifecycleNotificationProducer(
                new NotificationOutbox(outboxTransactions));
    }

    private static HomeOpsAgentProperties agentProperties() {
        return new HomeOpsAgentProperties(
                AGENT_ID,
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                Duration.ofMinutes(1),
                128,
                Duration.ofDays(1));
    }

    private static HomeOpsNotificationProperties notificationProperties(
            boolean enabled,
            int payloadMaximumBytes) {
        return new HomeOpsNotificationProperties(
                enabled,
                enabled ? WEBHOOK : null,
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                10,
                6,
                Duration.ofSeconds(5),
                Duration.ofMinutes(15),
                65_536,
                payloadMaximumBytes,
                Duration.ofDays(30),
                Duration.ofDays(90));
    }

    private void seedStatus(
            UUID snapshotId,
            String version,
            Instant capturedAt,
            Instant seenAt) {
        assertThat(statusStore.insertIfAbsent(
                AGENT_ID, snapshotId, version, capturedAt, seenAt)).isTrue();
    }

    private static AgentSnapshotRequest snapshot(String version, Instant capturedAt) {
        return new AgentSnapshotRequest(
                UUID.randomUUID(),
                AGENT_ID,
                version,
                capturedAt,
                new AgentSnapshotRequest.HostSnapshot(
                        25.0,
                        16_000,
                        8_000,
                        1_000_000,
                        500_000,
                        1_000),
                List.of());
    }

    private UUID markRootSent() {
        UUID id = notification("AGENT_STALE").id();
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE notification_event
                SET status = 'SENT', sent_at = ?, next_attempt_at = NULL,
                    terminal_at = ?, updated_at = ?
                WHERE id = ?
                """, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now), id);
        return id;
    }

    private int notificationCount(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notification_event WHERE event_type = ?",
                Integer.class,
                eventType);
    }

    private NotificationRow notification(String eventType) {
        return jdbc.queryForObject("""
                SELECT id, source_id, parent_notification_id, status
                FROM notification_event
                WHERE event_type = ?
                """, (row, index) -> new NotificationRow(
                row.getObject("id", UUID.class),
                row.getObject("source_id", UUID.class),
                row.getObject("parent_notification_id", UUID.class),
                row.getString("status")), eventType);
    }

    private List<String> notificationEventTypes() {
        return jdbc.queryForList(
                "SELECT event_type FROM notification_event ORDER BY occurred_at, id",
                String.class);
    }

    private String statusVersion() {
        return jdbc.queryForObject(
                "SELECT agent_version FROM agent_status WHERE agent_id = ?",
                String.class,
                AGENT_ID);
    }

    private Instant statusCapturedAt() {
        return jdbc.queryForObject(
                "SELECT last_captured_at FROM agent_status WHERE agent_id = ?",
                java.sql.Timestamp.class,
                AGENT_ID).toInstant();
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    private void race(Runnable first, Runnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> firstResult = executor.submit(() -> runWhenReleased(first, ready, start));
            Future<Void> secondResult = executor.submit(() -> runWhenReleased(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            await(firstResult);
            await(secondResult);
        }
    }

    private Void runWhenReleased(
            Runnable action,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        inTransaction(() -> {
            action.run();
            return null;
        });
        return null;
    }

    private void await(Future<Void> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError(
                    "Concurrent Agent lifecycle notification transaction did not complete",
                    exception);
        }
    }

    private record NotificationRow(
            UUID id,
            UUID sourceId,
            UUID parentId,
            String status) { }

    private static final class SignallingAgentStatusStore extends AgentStatusStore {
        private final CountDownLatch entered;

        private SignallingAgentStatusStore(
                JdbcTemplate jdbc,
                CountDownLatch entered) {
            super(jdbc);
            this.entered = entered;
        }

        @Override
        public java.util.Optional<AgentStatusSnapshot> findForUpdate(String agentId) {
            entered.countDown();
            return super.findForUpdate(agentId);
        }
    }
}
