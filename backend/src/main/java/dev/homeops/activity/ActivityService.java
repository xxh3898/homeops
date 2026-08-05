package dev.homeops.activity;

import dev.homeops.activity.ActivityStore.StoredActivity;
import dev.homeops.activity.api.ActivityPageResponse;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {
    private final ActivityStore store;
    private final Clock clock;

    @Autowired
    public ActivityService(ActivityStore store) {
        this(store, Clock.systemUTC());
    }

    ActivityService(ActivityStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ActivityPageResponse page(String encodedCursor, int limit) {
        ActivityCursor cursor = encodedCursor == null ? null : ActivityCursor.decode(encodedCursor);
        var snapshotAt = cursor == null ? clock.instant() : cursor.snapshotAt();
        List<StoredActivity> fetched = store.find(cursor, snapshotAt, limit + 1);
        boolean hasNext = fetched.size() > limit;
        List<StoredActivity> page = hasNext ? fetched.subList(0, limit) : fetched;
        String nextCursor = hasNext && !page.isEmpty()
                ? new ActivityCursor(snapshotAt, page.getLast().response().occurredAt(),
                        page.getLast().sortKey()).encode()
                : null;
        return new ActivityPageResponse(page.stream().map(StoredActivity::response).toList(),
                nextCursor, snapshotAt);
    }
}
