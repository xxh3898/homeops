package dev.homeops.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class ActivityStoreTest {
    private static final Instant SNAPSHOT_AT = Instant.parse("2026-08-06T12:00:00Z");
    @Mock private JdbcTemplate jdbcTemplate;

    @Test
    void should_useImmutableMembershipTime_when_activitySnapshotIsLoaded() {
        ActivityStore store = new ActivityStore(jdbcTemplate);

        store.find(null, SNAPSHOT_AT, 25);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(query.capture(), org.mockito.ArgumentMatchers
                .<RowMapper<ActivityStore.StoredActivity>>any(), eq(Timestamp.from(SNAPSHOT_AT)),
                isNull(), isNull(), eq(""), eq(25));
        assertThat(query.getValue().replaceAll("\\s+", " ")).contains(
                "d.started_at AS occurred_at, d.recorded_at AS recorded_at",
                "b.started_at, b.recorded_at",
                "a.occurred_at, a.recorded_at",
                "WHERE recorded_at <= ?",
                "i.opened_at, i.recorded_at",
                "'INCIDENT_OPEN:' || CAST(i.id AS text)",
                "'INCIDENT_RECOVERY:' || CAST(i.id AS text)",
                "WHERE i.resolved_at IS NOT NULL");
    }
}
