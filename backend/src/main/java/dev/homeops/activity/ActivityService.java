package dev.homeops.activity;

import dev.homeops.activity.ActivityStore.StoredActivity;
import dev.homeops.activity.api.ActivityPageResponse;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {
    private static final Duration CURSOR_MAXIMUM_VALIDITY = Duration.ofHours(1);

    private final ActivityStore store;
    private final Clock clock;
    private final ActivityCursorCodec cursorCodec;

    @Autowired
    public ActivityService(ActivityStore store) {
        this(store, Clock.systemUTC(), ActivityCursorCodec.processLocal());
    }

    ActivityService(ActivityStore store, Clock clock) {
        this(store, clock, ActivityCursorCodec.processLocal());
    }

    ActivityService(ActivityStore store, Clock clock, ActivityCursorCodec cursorCodec) {
        this.store = store;
        this.clock = clock;
        this.cursorCodec = cursorCodec;
    }

    @Transactional(readOnly = true)
    public ActivityPageResponse page(String encodedCursor, ActivityTypeFilter type, int limit) {
        ActivityCursor cursor = encodedCursor == null ? null : cursorCodec.decode(encodedCursor);
        Instant now = clock.instant();
        if (cursor != null) {
            validateCursor(cursor, type, now);
        }
        var snapshotAt = cursor == null ? now : cursor.snapshotAt();
        String visibilitySnapshot = cursor == null ? store.currentVisibilitySnapshot() : cursor.visibilitySnapshot();
        List<StoredActivity> fetched = store.find(cursor, visibilitySnapshot, type, limit + 1);
        boolean hasNext = fetched.size() > limit;
        List<StoredActivity> page = hasNext ? fetched.subList(0, limit) : fetched;
        String nextCursor = hasNext && !page.isEmpty()
                ? cursorCodec.encode(new ActivityCursor(snapshotAt, visibilitySnapshot,
                        page.getLast().response().occurredAt(), page.getLast().sortKey(), type))
                : null;
        return new ActivityPageResponse(page.stream().map(StoredActivity::response).toList(),
                nextCursor, snapshotAt);
    }

    private static void validateCursor(ActivityCursor cursor, ActivityTypeFilter type, Instant now) {
        if (cursor.scope() != type || cursor.snapshotAt().isAfter(now)) {
            throw new InvalidActivityCursorException();
        }
        try {
            if (!now.isBefore(cursor.snapshotAt().plus(CURSOR_MAXIMUM_VALIDITY))) {
                throw new InvalidActivityCursorException();
            }
        } catch (DateTimeException exception) {
            throw new InvalidActivityCursorException();
        }
    }
}
