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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Test
    void should_calculateBothRetentionSetsAndCurrentBoundary_when_removingResults() {
        MonitoredServiceStore store = new MonitoredServiceStore(jdbc);
        Instant healthyThreshold = Instant.parse("2026-08-01T00:00:00Z");
        Instant failureThreshold = Instant.parse("2026-07-01T00:00:00Z");

        store.deleteExpiredResults(healthyThreshold, failureThreshold);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(query.capture(), eq(Timestamp.from(healthyThreshold)), eq(Timestamp.from(failureThreshold)));
        assertThat(query.getValue().replaceAll("\\s+", " ")).contains(
                "WITH ordered_results AS",
                "classified_results AS",
                "current_streak_boundaries AS",
                "FIRST_VALUE(result.status)",
                "later.recency_rank < result.recency_rank",
                "ROW_NUMBER() OVER",
                "candidates.recency_rank > 100",
                "NOT EXISTS ( SELECT 1 FROM current_streak_boundaries boundary");
    }

    @Test
    void should_treatActiveIncidentConflictAsNormalCompetition_when_openingIncident() {
        MonitoredServiceStore store = new MonitoredServiceStore(jdbc);
        when(jdbc.query(
                any(String.class),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        java.util.Optional<java.util.UUID> inserted = store.openIncident(
                new dev.homeops.monitoring.api.MonitoredServiceResponse(
                java.util.UUID.randomUUID(), "HomeOps", "https://homeops.example.invalid/health", "GET", 200,
                3_000, 30, 3, 2, "WARNING", true, true), Instant.parse("2026-08-06T12:00:00Z"));

        assertThat(inserted).isEmpty();
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(
                query.capture(),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class));
        assertThat(query.getValue().replaceAll("\\s+", " ")).contains(
                "ON CONFLICT DO NOTHING", "RETURNING id");
    }

    @Test
    void should_recordResolutionTransactionIdentity_when_resolvingIncident() {
        MonitoredServiceStore store = new MonitoredServiceStore(jdbc);

        boolean resolved = store.resolveIncident(
                java.util.UUID.randomUUID(), Instant.parse("2026-08-06T12:00:00Z"));

        assertThat(resolved).isFalse();
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
