package dev.homeops.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class ActivityStoreTest {
    private static final String VISIBILITY_SNAPSHOT = "100:200:150";
    @Mock private JdbcTemplate jdbcTemplate;

    @Test
    void should_loadDatabaseVisibilitySnapshot_when_firstActivityPageIsRequested() {
        when(jdbcTemplate.queryForObject("SELECT pg_current_snapshot()::text", String.class))
                .thenReturn(VISIBILITY_SNAPSHOT);
        ActivityStore store = new ActivityStore(jdbcTemplate);

        assertThat(store.currentVisibilitySnapshot()).isEqualTo(VISIBILITY_SNAPSHOT);
    }

    @Test
    void should_useCommitVisibilitySnapshot_when_activitySnapshotIsLoaded() {
        ActivityStore store = new ActivityStore(jdbcTemplate);

        store.find(null, VISIBILITY_SNAPSHOT, ActivityTypeFilter.ALL, 25);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(query.capture(), org.mockito.ArgumentMatchers
                .<RowMapper<ActivityStore.StoredActivity>>any(), eq(VISIBILITY_SNAPSHOT),
                isNull(), isNull(), isNull(), isNull(), eq(""), eq(25));
        assertThat(query.getValue().replaceAll("\\s+", " ")).contains(
                "d.started_at AS occurred_at, d.recorded_xid AS recorded_xid",
                "b.started_at, b.recorded_xid",
                "a.occurred_at, a.recorded_xid",
                "c.requested_at, c.recorded_xid, c.container_id_prefix",
                "'CONTAINER_ACTION:' || CAST(c.id AS text)",
                "WHERE c.container_id_prefix ~ '^[0-9a-f]{12}$'",
                "WHERE pg_visible_in_snapshot(recorded_xid, ?::pg_snapshot)",
                "AND (?::text IS NULL OR event_type = ?::text)",
                "i.opened_at, i.recorded_xid",
                "COALESCE(i.resolved_xid, i.recorded_xid)",
                "'INCIDENT_OPEN:' || CAST(i.id AS text)",
                "'INCIDENT_RECOVERY:' || CAST(i.id AS text)",
                "WHERE i.resolved_at IS NOT NULL");
        assertThat(query.getValue()).doesNotContain(
                "c.principal",
                "c.idempotency_key",
                "c.container_name",
                "c.image",
                "c.failure_summary",
                "c.metadata",
                "c.reason_code");
    }

    @Test
    void should_bindExactEventTypeWithoutInterpolatingItIntoSql() {
        ActivityStore store = new ActivityStore(jdbcTemplate);

        store.find(null, VISIBILITY_SNAPSHOT, ActivityTypeFilter.INCIDENT, 25);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(query.capture(), org.mockito.ArgumentMatchers
                .<RowMapper<ActivityStore.StoredActivity>>any(), eq(VISIBILITY_SNAPSHOT),
                eq("INCIDENT"), eq("INCIDENT"), isNull(), isNull(), eq(""), eq(25));
        assertThat(query.getValue()).doesNotContain("event_type = 'INCIDENT'");
    }
}
