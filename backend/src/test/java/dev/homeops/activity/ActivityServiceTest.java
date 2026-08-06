package dev.homeops.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.homeops.activity.ActivityStore.StoredActivity;
import dev.homeops.activity.api.ActivityEventResponse;
import dev.homeops.activity.api.ActivityEventResponse.Severity;
import dev.homeops.activity.api.ActivityEventResponse.Type;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActivityServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final String VISIBILITY_SNAPSHOT = "100:200:150";
    @Mock private ActivityStore store;

    @Test
    void should_returnStableNextCursor_when_moreItemsExist() {
        StoredActivity first = activity("1", NOW, "DEPLOYMENT:1");
        StoredActivity second = activity("2", NOW.minusSeconds(1), "BACKUP:2");
        when(store.currentVisibilitySnapshot()).thenReturn(VISIBILITY_SNAPSHOT);
        when(store.find(isNull(), org.mockito.ArgumentMatchers.eq(VISIBILITY_SNAPSHOT),
                org.mockito.ArgumentMatchers.eq(2)))
                .thenReturn(List.of(first, second));
        ActivityService service = new ActivityService(store, Clock.fixed(NOW, ZoneOffset.UTC));

        var response = service.page(null, 1);

        assertThat(response.items()).containsExactly(first.response());
        assertThat(response.nextCursor()).isNotBlank();
        assertThat(ActivityCursor.decode(response.nextCursor()))
                .isEqualTo(new ActivityCursor(NOW, VISIBILITY_SNAPSHOT, NOW, "DEPLOYMENT:1"));
    }

    @Test
    void should_rejectRequest_when_cursorIsMalformed() {
        ActivityService service = new ActivityService(store, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.page("not-a-cursor", 25))
                .isInstanceOf(InvalidActivityCursorException.class);
    }

    @Test
    void should_rejectRequest_when_cursorTimestampIsInvalid() {
        ActivityService service = new ActivityService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "not-an-instant\n100:200:\n2026-08-06T12:00:00Z\nDEPLOYMENT:1"
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.page(cursor, 25))
                .isInstanceOf(InvalidActivityCursorException.class);
    }

    @Test
    void should_rejectRequest_when_cursorVisibilitySnapshotIsInvalid() {
        ActivityService service = new ActivityService(store, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.page(cursorWithVisibilitySnapshot("not-a-snapshot"), 25))
                .isInstanceOf(InvalidActivityCursorException.class);
    }

    @Test
    void should_rejectRequest_when_cursorVisibilitySnapshotViolatesPostgresSemantics() {
        ActivityService service = new ActivityService(store, Clock.fixed(NOW, ZoneOffset.UTC));

        for (String snapshot : List.of("2:1:", "0:1:", "1:2:0", "1:2:2", "1:4:3,2")) {
            assertThatThrownBy(() -> service.page(cursorWithVisibilitySnapshot(snapshot), 25))
                    .isInstanceOf(InvalidActivityCursorException.class);
        }

        verifyNoInteractions(store);
    }

    @Test
    void should_decodeCursor_when_visibilitySnapshotUsesUnsigned64Boundary() {
        String largestXid = "18446744073709551615";
        String snapshot = "18446744073709551614:" + largestXid
                + ":18446744073709551614,18446744073709551614";

        assertThat(ActivityCursor.decode(cursorWithVisibilitySnapshot(snapshot)).visibilitySnapshot())
                .isEqualTo(snapshot);
    }

    private static String cursorWithVisibilitySnapshot(String snapshot) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("2026-08-06T12:00:00Z\n" + snapshot + "\n2026-08-06T12:00:00Z\nDEPLOYMENT:1")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static StoredActivity activity(String id, Instant occurredAt, String sortKey) {
        return new StoredActivity(new ActivityEventResponse(id, Type.DEPLOYMENT, "Deployment", "SUCCESS",
                Severity.INFO, occurredAt, "abcdef123456"), sortKey);
    }
}
