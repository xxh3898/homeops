package dev.homeops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.homeops.common.DuplicateMonitoredServiceNameException;
import dev.homeops.monitoring.api.MonitoredServiceRequest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class MonitoredServiceStoreTest {
    @Mock private JdbcTemplate jdbc;

    @Test
    void should_rejectDuplicateName_when_databaseConflictPreventsInsert() {
        MonitoredServiceStore store = new MonitoredServiceStore(jdbc);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> store.create(request()))
                .isInstanceOf(DuplicateMonitoredServiceNameException.class);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(query.capture(), any(Object[].class));
        assertThat(query.getValue().replaceAll("\\s+", " ")).contains(
                "ON CONFLICT ON CONSTRAINT uk_monitored_service_name DO NOTHING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEALTHY", "DOWN"})
    void should_deleteExpiredCompletedStreakButPreserveCurrentStreak_when_removingResults(String status) {
        MonitoredServiceStore store = new MonitoredServiceStore(jdbc);
        Instant threshold = Instant.parse("2026-08-01T00:00:00Z");

        store.deleteResultsOlderThan(status, threshold);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(query.capture(), eq(status), eq(Timestamp.from(threshold)));
        assertThat(query.getValue().replaceAll("\\s+", " ")).contains(
                "later.service_id = result.service_id",
                "later.status <> result.status",
                "later.checked_at > result.checked_at",
                "ROW_NUMBER() OVER",
                "candidates.status_rank > 100")
                .doesNotContain("later.status = result.status");
    }

    @Test
    void should_treatActiveIncidentConflictAsNormalCompetition_when_openingIncident() {
        MonitoredServiceStore store = new MonitoredServiceStore(jdbc);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(0);

        boolean inserted = store.openIncident(new dev.homeops.monitoring.api.MonitoredServiceResponse(
                java.util.UUID.randomUUID(), "HomeOps", "https://homeops.example.invalid/health", "GET", 200,
                3_000, 30, 3, 2, "WARNING", true, true), Instant.parse("2026-08-06T12:00:00Z"));

        assertThat(inserted).isFalse();
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(query.capture(), any(Object[].class));
        assertThat(query.getValue().replaceAll("\\s+", " ")).contains("ON CONFLICT DO NOTHING");
    }

    @Test
    void should_recordResolutionTransactionIdentity_when_resolvingIncident() {
        MonitoredServiceStore store = new MonitoredServiceStore(jdbc);

        store.resolveIncident(java.util.UUID.randomUUID(), Instant.parse("2026-08-06T12:00:00Z"));

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(query.capture(), any(Object[].class));
        assertThat(query.getValue().replaceAll("\\s+", " ")).contains(
                "resolved_xid = pg_current_xact_id()");
    }

    private static MonitoredServiceRequest request() {
        return new MonitoredServiceRequest("HomeOps", "https://homeops.example.invalid/health",
                MonitoredServiceRequest.Method.GET, 200, 3_000, 30, 3, 2,
                MonitoredServiceRequest.Severity.WARNING, true, true);
    }
}
