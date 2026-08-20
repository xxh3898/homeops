package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homeops.agent.AgentSnapshotService;
import dev.homeops.agent.api.AgentSnapshotRequest;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerHealth;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerSnapshot;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerState;
import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.agent.persistence.AgentActivityStore;
import dev.homeops.agent.persistence.AgentStatusRepository;
import dev.homeops.agent.persistence.AgentStatusStore;
import dev.homeops.agent.persistence.HostMetricAggregateRepository;
import dev.homeops.agent.persistence.ProcessedAgentSnapshotStore;
import dev.homeops.notification.config.ContainerNotificationProperties;
import dev.homeops.notification.config.HomeOpsNotificationProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class ContainerNotificationProducerPostgresqlIntegrationTest {
    private static final String AGENT_ID = "local-mac";
    private static final String FULL_ID = "0123456789abcdef".repeat(4);
    private static final String RECREATED_ID = "fedcba9876543210".repeat(4);
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
        jdbc.update("DELETE FROM container_notification_state");
        jdbc.update("DELETE FROM agent_event");
        jdbc.update("DELETE FROM host_metric_aggregate");
        jdbc.update("DELETE FROM processed_agent_snapshot");
        jdbc.update("DELETE FROM agent_status");
        statusStore = new AgentStatusStore(jdbc);
        processedSnapshots = new ProcessedAgentSnapshotStore(jdbc);
        activities = new AgentActivityStore(jdbc);
    }

    @Test
    void should_persistBaselineThenOneSuppressedRoot_when_failurePersists() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AgentSnapshotService service = service(false, 8_192);
        AgentSnapshotRequest baseline = snapshot(
                now.minusSeconds(7), FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true);

        inTransaction(() -> service.accept(baseline));

        StateRow baselineState = state();
        assertThat(baselineState.failureStartedAt()).isEqualTo(baseline.capturedAt());
        assertThat(baselineState.activeEpisodeId()).isNull();
        assertThat(notificationCount()).isZero();

        inTransaction(() -> service.accept(snapshot(
                now.minusSeconds(1), FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true)));
        inTransaction(() -> service.accept(snapshot(
                now.minusMillis(500), FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true)));

        assertThat(notificationCount("CONTAINER_FAILED")).isEqualTo(1);
        NotificationRow root = notification("CONTAINER_FAILED");
        assertThat(root.status()).isEqualTo("SUPPRESSED");
        assertThat(root.severity()).isEqualTo("CRITICAL");
        assertThat(root.sourceId()).isEqualTo(state().activeEpisodeId());
        assertThat(root.parentId()).isNull();
        assertThat(root.payload())
                .contains("api", "project", "EXITED", "NONE", "FAILED")
                .doesNotContain(
                        FULL_ID,
                        "private.example.invalid",
                        "raw status",
                        "instance_fingerprint",
                        "cpuUsagePercent",
                        "memoryUsageBytes",
                        "labels",
                        "ports");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'container_notification_state'
                  AND column_name IN ('container_id', 'image', 'status', 'labels', 'metrics')
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM container_notification_state
                WHERE instance_fingerprint = ? OR logical_identity_hash = ?
                """, Integer.class, FULL_ID, FULL_ID)).isZero();
    }

    @Test
    void should_createRecoveryChildWithSentRootParent_when_containerRecovers() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AgentSnapshotService service = service(true, 8_192);
        inTransaction(() -> service.accept(snapshot(
                now.minusSeconds(8), FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true)));
        inTransaction(() -> service.accept(snapshot(
                now.minusSeconds(2), FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true)));
        UUID rootId = markRootSent();
        UUID episodeId = notification("CONTAINER_FAILED").sourceId();

        inTransaction(() -> service.accept(snapshot(
                now.minusSeconds(1), FULL_ID,
                ContainerState.RUNNING, ContainerHealth.HEALTHY, true)));

        NotificationRow recovery = notification("CONTAINER_RECOVERED");
        assertThat(recovery.sourceId()).isEqualTo(episodeId);
        assertThat(recovery.parentId()).isEqualTo(rootId);
        assertThat(recovery.severity()).isEqualTo("RECOVERY");
        assertThat(recovery.status()).isEqualTo("PENDING");
        assertThat(state().activeEpisodeId()).isNull();
        assertThat(state().failureStartedAt()).isNull();
    }

    @Test
    void should_notReplaySuppressedRootOrCreateRecovery_when_notificationsEnableLater() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AgentSnapshotService disabled = service(false, 8_192);
        inTransaction(() -> disabled.accept(snapshot(
                now.minusSeconds(8), FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true)));
        inTransaction(() -> disabled.accept(snapshot(
                now.minusSeconds(2), FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true)));

        AgentSnapshotService enabled = service(true, 8_192);
        inTransaction(() -> enabled.accept(snapshot(
                now.minusSeconds(1), FULL_ID,
                ContainerState.RUNNING, ContainerHealth.HEALTHY, true)));

        assertThat(notificationTypes()).containsExactly("CONTAINER_FAILED");
        assertThat(notification("CONTAINER_FAILED").status()).isEqualTo("SUPPRESSED");
        assertThat(state().activeEpisodeId()).isNull();
    }

    @Test
    void should_resetWithoutNotification_when_authorityInstanceAndPresenceChange() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AgentSnapshotService service = service(false, 8_192);
        inTransaction(() -> service.accept(snapshot(
                now.minusSeconds(5), FULL_ID, ContainerState.RUNNING, ContainerHealth.HEALTHY, true)));

        inTransaction(() -> service.accept(snapshot(
                now.minusSeconds(4), FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, false)));
        assertThat(state().notificationsAllowed()).isFalse();
        assertThat(state().failureStartedAt()).isNull();

        inTransaction(() -> service.accept(snapshot(
                now.minusSeconds(3), FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true)));
        assertThat(state().failureStartedAt()).isEqualTo(now.minusSeconds(3));
        assertThat(notificationCount()).isZero();

        String priorFingerprint = state().instanceFingerprint();
        inTransaction(() -> service.accept(snapshot(
                now.minusSeconds(2), RECREATED_ID,
                ContainerState.EXITED, ContainerHealth.NONE, true)));
        assertThat(state().instanceFingerprint()).isNotEqualTo(priorFingerprint);
        assertThat(state().failureStartedAt()).isEqualTo(now.minusSeconds(2));
        assertThat(notificationCount()).isZero();

        inTransaction(() -> service.accept(snapshot(now.minusSeconds(1), List.of())));
        assertThat(stateCount()).isZero();
        assertThat(notificationCount()).isZero();
    }

    @Test
    void should_ignoreDuplicateEqualOlderAndStaleSnapshots_forContainerAuthority() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AgentSnapshotService service = service(false, 8_192);
        AgentSnapshotRequest baseline = snapshot(
                now.minusSeconds(3), FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true);
        inTransaction(() -> service.accept(baseline));

        inTransaction(() -> service.accept(baseline));
        inTransaction(() -> service.accept(snapshot(
                baseline.capturedAt(), FULL_ID,
                ContainerState.RUNNING, ContainerHealth.HEALTHY, true)));
        inTransaction(() -> service.accept(snapshot(
                now.minusSeconds(4), FULL_ID,
                ContainerState.RUNNING, ContainerHealth.HEALTHY, true)));

        assertThat(state().lastSnapshotId()).isEqualTo(baseline.snapshotId());
        assertThat(state().state()).isEqualTo("EXITED");
        assertThat(state().failureStartedAt()).isEqualTo(baseline.capturedAt());

        clearRows();
        inTransaction(() -> service(false, 8_192).accept(snapshot(
                Instant.now().minusSeconds(40), FULL_ID,
                ContainerState.EXITED, ContainerHealth.NONE, true)));
        assertThat(stateCount()).isZero();
        assertThat(notificationCount()).isZero();
    }

    @Test
    void should_createOneRoot_when_concurrentQualifyingSnapshotsRace() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        inTransaction(() -> service(false, 8_192).accept(snapshot(
                now.minusSeconds(8), FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true)));
        AgentSnapshotRequest first = snapshot(
                now.minusSeconds(2), FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true);
        AgentSnapshotRequest second = snapshot(
                now.minusSeconds(1), FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true);

        race(
                () -> service(false, 8_192).accept(first),
                () -> service(false, 8_192).accept(second));

        assertThat(notificationCount("CONTAINER_FAILED")).isEqualTo(1);
        assertThat(state().activeEpisodeId())
                .isEqualTo(notification("CONTAINER_FAILED").sourceId());
    }

    @Test
    void should_rollBackSnapshotMetricAndState_whenRootPayloadEncodingFails() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AgentSnapshotRequest baseline = snapshot(
                now.minusSeconds(8), FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true);
        inTransaction(() -> service(false, 8_192).accept(baseline));
        AgentSnapshotRequest qualifying = snapshot(
                now.minusSeconds(1), FULL_ID, ContainerState.EXITED, ContainerHealth.NONE, true);

        assertThatThrownBy(() -> inTransaction(
                () -> service(false, 1).accept(qualifying)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification payload exceeds the serialized byte limit");

        assertThat(jdbc.queryForObject(
                "SELECT last_snapshot_id FROM agent_status WHERE agent_id = ?",
                UUID.class,
                AGENT_ID)).isEqualTo(baseline.snapshotId());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM processed_agent_snapshot", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT sample_count FROM host_metric_aggregate", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM agent_event", Integer.class)).isEqualTo(1);
        assertThat(state().lastSnapshotId()).isEqualTo(baseline.snapshotId());
        assertThat(state().activeEpisodeId()).isNull();
        assertThat(notificationCount()).isZero();
    }

    private AgentSnapshotService service(boolean enabled, int payloadMaximumBytes) {
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
        NotificationOutbox outbox = new NotificationOutbox(outboxTransactions);
        return new AgentSnapshotService(
                agentProperties(),
                statusRepository,
                statusStore,
                metricRepository,
                processedSnapshots,
                activities,
                new AgentLifecycleNotificationProducer(outbox),
                new ContainerNotificationProducer(
                        new ContainerNotificationStateStore(jdbc),
                        outbox,
                        new ContainerNotificationProperties(
                                Duration.ofSeconds(5), Duration.ofSeconds(30))));
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

    private static AgentSnapshotRequest snapshot(
            Instant capturedAt,
            String id,
            ContainerState state,
            ContainerHealth health,
            boolean allowed) {
        return snapshot(capturedAt, List.of(container(id, state, health, allowed)));
    }

    private static AgentSnapshotRequest snapshot(
            Instant capturedAt,
            List<ContainerSnapshot> containers) {
        return new AgentSnapshotRequest(
                UUID.randomUUID(),
                AGENT_ID,
                "v1",
                capturedAt,
                false,
                new AgentSnapshotRequest.HostSnapshot(
                        10.0, 16_000, 8_000, 1_000_000, 500_000, 1_000),
                containers);
    }

    private static ContainerSnapshot container(
            String id,
            ContainerState state,
            ContainerHealth health,
            boolean allowed) {
        return new ContainerSnapshot(
                id,
                "api",
                "project",
                "private.example.invalid/image:tag",
                state,
                health,
                "raw status with token=synthetic",
                null,
                0,
                1.0,
                100L,
                200L,
                List.of(),
                false,
                false,
                allowed);
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> action) {
        return transactions.execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private void race(
            java.util.concurrent.Callable<?> first,
            java.util.concurrent.Callable<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> firstResult = executor.submit(() -> concurrent(first, ready, start));
            Future<?> secondResult = executor.submit(() -> concurrent(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstResult.get(5, TimeUnit.SECONDS);
            secondResult.get(5, TimeUnit.SECONDS);
        }
    }

    private Object concurrent(
            java.util.concurrent.Callable<?> action,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return inTransaction(action);
    }

    private UUID markRootSent() {
        NotificationRow root = notification("CONTAINER_FAILED");
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE notification_event
                SET status = 'SENT', sent_at = ?, next_attempt_at = NULL,
                    terminal_at = ?, updated_at = ?
                WHERE id = ?
                """,
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now),
                root.id());
        return root.id();
    }

    private int stateCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM container_notification_state", Integer.class);
    }

    private StateRow state() {
        return jdbc.queryForObject("""
                SELECT notifications_allowed, state, instance_fingerprint,
                       last_snapshot_id, failure_started_at, active_episode_id
                FROM container_notification_state
                """, (row, index) -> new StateRow(
                row.getBoolean("notifications_allowed"),
                row.getString("state"),
                row.getString("instance_fingerprint"),
                row.getObject("last_snapshot_id", UUID.class),
                row.getTimestamp("failure_started_at") == null
                        ? null : row.getTimestamp("failure_started_at").toInstant(),
                row.getObject("active_episode_id", UUID.class)));
    }

    private int notificationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM notification_event", Integer.class);
    }

    private int notificationCount(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notification_event WHERE event_type = ?",
                Integer.class,
                eventType);
    }

    private NotificationRow notification(String eventType) {
        return jdbc.queryForObject("""
                SELECT id, source_id, parent_notification_id, severity, status, payload::text
                FROM notification_event
                WHERE event_type = ?
                """, (row, index) -> new NotificationRow(
                row.getObject("id", UUID.class),
                row.getObject("source_id", UUID.class),
                row.getObject("parent_notification_id", UUID.class),
                row.getString("severity"),
                row.getString("status"),
                row.getString("payload")), eventType);
    }

    private List<String> notificationTypes() {
        return jdbc.queryForList(
                "SELECT event_type FROM notification_event ORDER BY occurred_at, id",
                String.class);
    }

    private record StateRow(
            boolean notificationsAllowed,
            String state,
            String instanceFingerprint,
            UUID lastSnapshotId,
            Instant failureStartedAt,
            UUID activeEpisodeId) { }

    private record NotificationRow(
            UUID id,
            UUID sourceId,
            UUID parentId,
            String severity,
            String status,
            String payload) { }
}
