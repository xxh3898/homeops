package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.homeops.monitoring.MonitoredServiceNotFoundException;
import dev.homeops.monitoring.MonitoredServiceStore;
import dev.homeops.monitoring.SafeServiceUrlPolicy;
import dev.homeops.monitoring.api.MonitoredServiceRequest;
import dev.homeops.monitoring.api.MonitoredServiceResponse;
import dev.homeops.monitoring.api.MonitoredServiceNotificationResponse;
import dev.homeops.monitoring.api.MonitoringService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class MonitoredServiceNotificationAuthorityPostgresqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    private PostgresqlNotificationTestDatabase database;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createSchema() {
        database = PostgresqlNotificationTestDatabase.create();
        jdbc = database.jdbc();
    }

    @AfterEach
    void dropSchema() {
        database.close();
    }

    @Test
    void should_migrateFromV1ToV11AndResetLegacyAuthority_when_existingRowUsesTrueDefault() {
        database.migrateTo("1");
        UUID serviceId = insertServiceWithoutNotificationAuthority("legacy-v1");
        assertThat(authority(serviceId)).isTrue();

        Flyway flyway = database.migrateToCurrent();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("11");
        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM monitored_service WHERE id = ? AND name = 'legacy-v1'",
                Integer.class, serviceId)).isEqualTo(1);
        assertThat(authority(serviceId)).isFalse();
    }

    @Test
    void should_migrateFromV7ToV11AndPreserveRowsWithFailClosedDefault() {
        database.migrateTo("7");
        UUID enabled = insertService("legacy-enabled", true);
        UUID disabled = insertService("legacy-disabled", false);

        database.migrateToCurrent();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM monitored_service", Integer.class))
                .isEqualTo(2);
        assertThat(authority(enabled)).isFalse();
        assertThat(authority(disabled)).isFalse();

        UUID defaulted = insertServiceWithoutNotificationAuthority("v8-default");
        assertThat(authority(defaulted)).isFalse();
    }

    @Test
    void should_preserveV7ApplicationReadAndCreateShape_when_schemaIsV11() {
        database.migrateToCurrent();
        MonitoredServiceStore previousApplicationStore = new MonitoredServiceStore(jdbc);

        MonitoredServiceResponse created = previousApplicationStore.create(request(true));

        assertThat(created.notificationEnabled()).isTrue();
        assertThat(previousApplicationStore.list())
                .extracting(MonitoredServiceResponse::notificationEnabled)
                .containsExactly(true);
    }

    @Test
    void should_changeOnlyFutureAuthorityWithoutReplay_when_incidentHistoryExists() {
        database.migrateToCurrent();
        UUID serviceId = insertService("authority", false);
        insertHealthResult(serviceId);
        insertOpenIncident(serviceId);
        MonitoringService service = service();

        MonitoredServiceNotificationResponse enabled = inTransaction(
                () -> service.updateNotificationAuthority(serviceId, true));
        MonitoredServiceNotificationResponse repeated = inTransaction(
                () -> service.updateNotificationAuthority(serviceId, true));
        MonitoredServiceNotificationResponse disabled = inTransaction(
                () -> service.updateNotificationAuthority(serviceId, false));

        assertThat(enabled.notificationEnabled()).isTrue();
        assertThat(repeated.notificationEnabled()).isTrue();
        assertThat(disabled.notificationEnabled()).isFalse();
        assertThat(authority(serviceId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM health_check_result", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_event", Integer.class))
                .isZero();
    }

    @Test
    void should_failClosedWithoutCreatingRows_when_serviceDoesNotExist() {
        database.migrateToCurrent();
        MonitoringService service = service();
        UUID missingId = UUID.fromString("10000000-0000-0000-0000-000000000404");

        assertThatThrownBy(() -> inTransaction(
                () -> service.updateNotificationAuthority(missingId, true)))
                .isInstanceOf(MonitoredServiceNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM monitored_service", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_event", Integer.class))
                .isZero();
    }

    private MonitoringService service() {
        return new MonitoringService(
                new MonitoredServiceStore(jdbc), mock(SafeServiceUrlPolicy.class));
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> action) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(database.dataSource()));
        return transaction.execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private UUID insertService(String name, boolean notificationEnabled) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO monitored_service
                    (id, name, url, http_method, expected_status, timeout_ms, interval_seconds,
                     failure_threshold, recovery_threshold, severity, enabled, notification_enabled)
                VALUES (?, ?, ?, 'GET', 200, 3000, 30, 3, 2, 'WARNING', TRUE, ?)
                """, id, name, "https://" + name + ".example.invalid/health", notificationEnabled);
        return id;
    }

    private UUID insertServiceWithoutNotificationAuthority(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO monitored_service
                    (id, name, url, http_method, expected_status, timeout_ms, interval_seconds,
                     failure_threshold, recovery_threshold, severity, enabled)
                VALUES (?, ?, ?, 'GET', 200, 3000, 30, 3, 2, 'WARNING', TRUE)
                """, id, name, "https://" + name + ".example.invalid/health");
        return id;
    }

    private boolean authority(UUID serviceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT notification_enabled FROM monitored_service WHERE id = ?",
                Boolean.class, serviceId));
    }

    private void insertHealthResult(UUID serviceId) {
        jdbc.update("""
                INSERT INTO health_check_result
                    (id, service_id, checked_at, status, http_status, response_time_ms)
                VALUES (?, ?, ?, 'DOWN', 503, 50)
                """, UUID.randomUUID(), serviceId, Timestamp.from(NOW));
    }

    private void insertOpenIncident(UUID serviceId) {
        jdbc.update("""
                INSERT INTO incident
                    (id, service_id, incident_type, severity, status, title,
                     opened_at, last_observed_at)
                VALUES (?, ?, 'HEALTH_CHECK', 'WARNING', 'OPEN',
                        'Synthetic integration incident', ?, ?)
                """, UUID.randomUUID(), serviceId, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private static MonitoredServiceRequest request(boolean notificationEnabled) {
        return new MonitoredServiceRequest(
                "previous-application", "https://previous-application.example.invalid/health",
                MonitoredServiceRequest.Method.GET, 200, 3_000, 30, 3, 2,
                MonitoredServiceRequest.Severity.WARNING, true, notificationEnabled);
    }
}
