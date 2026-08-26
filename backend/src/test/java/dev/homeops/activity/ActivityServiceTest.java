package dev.homeops.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.homeops.activity.ActivityStore.StoredActivity;
import dev.homeops.activity.api.ActivityEventResponse;
import dev.homeops.activity.api.ActivityEventResponse.Severity;
import dev.homeops.activity.api.ActivityEventResponse.Type;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
    private static final String DEPLOYMENT_SORT_KEY = "DEPLOYMENT:10000000-0000-0000-0000-000000000001";
    private static final byte[] KEY = "activity-service-test-key-32bytes".getBytes(StandardCharsets.UTF_8);

    @Mock private ActivityStore store;

    @Test
    void should_returnStableAuthenticatedNextCursor_when_moreItemsExist() {
        StoredActivity first = activity("1", NOW, DEPLOYMENT_SORT_KEY);
        StoredActivity second = activity("2", NOW.minusSeconds(1),
                "BACKUP:10000000-0000-0000-0000-000000000002");
        when(store.currentVisibilitySnapshot()).thenReturn(VISIBILITY_SNAPSHOT);
        when(store.find(isNull(), eq(VISIBILITY_SNAPSHOT), eq(ActivityTypeFilter.ALL), eq(2)))
                .thenReturn(List.of(first, second));
        ActivityCursorCodec codec = codec();
        ActivityService service = service(Clock.fixed(NOW, ZoneOffset.UTC), codec);

        var response = service.page(null, ActivityTypeFilter.ALL, 1);

        assertThat(response.items()).containsExactly(first.response());
        assertThat(response.nextCursor()).startsWith("v1.");
        assertThat(codec.decode(response.nextCursor()))
                .isEqualTo(new ActivityCursor(
                        NOW, VISIBILITY_SNAPSHOT, NOW, DEPLOYMENT_SORT_KEY, ActivityTypeFilter.ALL));
    }

    @Test
    void should_acceptCursor_when_nowIsImmediatelyBeforeExpiry() {
        ActivityCursor cursor = validCursor(ActivityTypeFilter.ALL);
        when(store.find(eq(cursor), eq(VISIBILITY_SNAPSHOT), eq(ActivityTypeFilter.ALL), eq(26)))
                .thenReturn(List.of());
        Instant immediatelyBeforeExpiry = NOW.plus(Duration.ofHours(1)).minusNanos(1);
        ActivityService service = service(Clock.fixed(immediatelyBeforeExpiry, ZoneOffset.UTC), codec());

        assertThat(service.page(codec().encode(cursor), ActivityTypeFilter.ALL, 25).items()).isEmpty();

        verify(store).find(cursor, VISIBILITY_SNAPSHOT, ActivityTypeFilter.ALL, 26);
    }

    @Test
    void should_rejectCursorWithoutStoreAccess_when_nowIsExactlyAtExpiry() {
        ActivityService service = service(
                Clock.fixed(NOW.plus(Duration.ofHours(1)), ZoneOffset.UTC), codec());

        assertThatThrownBy(() -> service.page(codec().encode(validCursor(ActivityTypeFilter.ALL)),
                ActivityTypeFilter.ALL, 25))
                .isInstanceOf(InvalidActivityCursorException.class);

        verifyNoInteractions(store);
    }

    @Test
    void should_rejectCursorWithoutStoreAccess_when_nowIsAfterExpiry() {
        ActivityService service = service(
                Clock.fixed(NOW.plus(Duration.ofHours(1)).plusNanos(1), ZoneOffset.UTC), codec());

        assertThatThrownBy(() -> service.page(codec().encode(validCursor(ActivityTypeFilter.ALL)),
                ActivityTypeFilter.ALL, 25))
                .isInstanceOf(InvalidActivityCursorException.class);

        verifyNoInteractions(store);
    }

    @Test
    void should_preserveFirstPageSnapshotAndNotExtendValidity_when_pagesContinue() {
        StoredActivity first = activity("1", NOW, DEPLOYMENT_SORT_KEY);
        StoredActivity second = activity("2", NOW.minusSeconds(1),
                "BACKUP:10000000-0000-0000-0000-000000000002");
        StoredActivity third = activity("3", NOW.minusSeconds(2),
                "AGENT:10000000-0000-0000-0000-000000000003");
        MutableClock clock = new MutableClock(NOW);
        ActivityCursorCodec codec = codec();
        ActivityService service = service(clock, codec);
        when(store.currentVisibilitySnapshot()).thenReturn(VISIBILITY_SNAPSHOT);
        when(store.find(isNull(), eq(VISIBILITY_SNAPSHOT), eq(ActivityTypeFilter.ALL), eq(2)))
                .thenReturn(List.of(first, second));

        var firstPage = service.page(null, ActivityTypeFilter.ALL, 1);
        ActivityCursor firstCursor = codec.decode(firstPage.nextCursor());
        when(store.find(eq(firstCursor), eq(VISIBILITY_SNAPSHOT), eq(ActivityTypeFilter.ALL), eq(2)))
                .thenReturn(List.of(second, third));
        clock.set(NOW.plus(Duration.ofMinutes(30)));
        var secondPage = service.page(firstPage.nextCursor(), ActivityTypeFilter.ALL, 1);

        assertThat(codec.decode(secondPage.nextCursor()).snapshotAt()).isEqualTo(NOW);

        clearInvocations(store);
        clock.set(NOW.plus(Duration.ofHours(1)));
        assertThatThrownBy(() -> service.page(secondPage.nextCursor(), ActivityTypeFilter.ALL, 1))
                .isInstanceOf(InvalidActivityCursorException.class);
        verifyNoInteractions(store);
    }

    @Test
    void should_rejectCursorWithoutStoreAccess_when_cursorScopeDiffersFromRequestedType() {
        ActivityService service = service(Clock.fixed(NOW, ZoneOffset.UTC), codec());
        String cursor = codec().encode(validCursor(ActivityTypeFilter.DEPLOYMENT));

        assertThatThrownBy(() -> service.page(cursor, ActivityTypeFilter.BACKUP, 25))
                .isInstanceOf(InvalidActivityCursorException.class);

        verifyNoInteractions(store);
    }

    @Test
    void should_rejectCursorWithoutStoreAccess_when_cursorWasSignedByDifferentProcess() {
        ActivityCursorCodec otherProcess = new ActivityCursorCodec(
                "other-activity-service-test-key!!".getBytes(StandardCharsets.UTF_8));
        ActivityService service = service(Clock.fixed(NOW, ZoneOffset.UTC), codec());

        assertThatThrownBy(() -> service.page(otherProcess.encode(validCursor(ActivityTypeFilter.ALL)),
                ActivityTypeFilter.ALL, 25))
                .isInstanceOf(InvalidActivityCursorException.class);

        verifyNoInteractions(store);
    }

    @Test
    void should_rejectCursorWithoutStoreAccess_when_cursorPayloadIsTampered() {
        String encoded = codec().encode(validCursor(ActivityTypeFilter.ALL));
        String[] parts = encoded.split("\\.");
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        payload[0] ^= 1;
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "." + parts[2];
        ActivityService service = service(Clock.fixed(NOW, ZoneOffset.UTC), codec());

        assertThatThrownBy(() -> service.page(tampered, ActivityTypeFilter.ALL, 25))
                .isInstanceOf(InvalidActivityCursorException.class);

        verifyNoInteractions(store);
    }

    @Test
    void should_rejectCursorWithoutStoreAccess_when_envelopeIsUnknownMalformedOrOversized() {
        ActivityService service = service(Clock.fixed(NOW, ZoneOffset.UTC), codec());
        String unknownVersion = codec().encode(validCursor(ActivityTypeFilter.ALL))
                .replaceFirst("^v1\\.", "v2.");

        for (String encoded : List.of("not-a-cursor", unknownVersion, "a".repeat(4097))) {
            assertThatThrownBy(() -> service.page(encoded, ActivityTypeFilter.ALL, 25))
                    .isInstanceOf(InvalidActivityCursorException.class);
        }

        verifyNoInteractions(store);
    }

    @Test
    void should_rejectCursorWithoutStoreAccess_when_cursorTimestampIsOutsidePostgresqlRange() {
        ActivityCursor invalid = new ActivityCursor(
                Instant.parse("+1000000000-12-31T23:59:59.999999999Z"), VISIBILITY_SNAPSHOT, NOW,
                DEPLOYMENT_SORT_KEY, ActivityTypeFilter.ALL);
        ActivityService service = service(Clock.fixed(NOW, ZoneOffset.UTC), codec());

        assertThatThrownBy(() -> service.page(codec().encode(invalid), ActivityTypeFilter.ALL, 25))
                .isInstanceOf(InvalidActivityCursorException.class);

        verifyNoInteractions(store);
    }

    @Test
    void should_rejectCursorWithoutStoreAccess_when_visibilitySnapshotViolatesPostgresSemantics() {
        ActivityService service = service(Clock.fixed(NOW, ZoneOffset.UTC), codec());

        for (String snapshot : List.of("2:1:", "0:1:", "1:2:0", "1:2:2", "1:4:3,2")) {
            ActivityCursor invalid = new ActivityCursor(
                    NOW, snapshot, NOW, DEPLOYMENT_SORT_KEY, ActivityTypeFilter.ALL);
            assertThatThrownBy(() -> service.page(codec().encode(invalid), ActivityTypeFilter.ALL, 25))
                    .isInstanceOf(InvalidActivityCursorException.class);
        }

        verifyNoInteractions(store);
    }

    @Test
    void should_rejectCursorWithoutStoreAccess_when_unsignedLegacyCursorHasFourParts() {
        String legacy = unsignedLegacyCursor(false);
        ActivityService service = service(Clock.fixed(NOW, ZoneOffset.UTC), codec());

        assertThatThrownBy(() -> service.page(legacy, ActivityTypeFilter.ALL, 25))
                .isInstanceOf(InvalidActivityCursorException.class);

        verifyNoInteractions(store);
    }

    @Test
    void should_rejectCursorWithoutStoreAccess_when_unsignedLegacyCursorHasFiveParts() {
        String legacy = unsignedLegacyCursor(true);
        ActivityService service = service(Clock.fixed(NOW, ZoneOffset.UTC), codec());

        assertThatThrownBy(() -> service.page(legacy, ActivityTypeFilter.ALL, 25))
                .isInstanceOf(InvalidActivityCursorException.class);

        verifyNoInteractions(store);
    }

    @Test
    void should_rejectCursorWithoutStoreAccess_when_snapshotIsInTheFuture() {
        ActivityCursor future = new ActivityCursor(
                NOW.plusSeconds(1), VISIBILITY_SNAPSHOT, NOW, DEPLOYMENT_SORT_KEY, ActivityTypeFilter.ALL);
        ActivityService service = service(Clock.fixed(NOW, ZoneOffset.UTC), codec());

        assertThatThrownBy(() -> service.page(codec().encode(future), ActivityTypeFilter.ALL, 25))
                .isInstanceOf(InvalidActivityCursorException.class);

        verifyNoInteractions(store);
    }

    private ActivityService service(Clock clock, ActivityCursorCodec codec) {
        return new ActivityService(store, clock, codec);
    }

    private static ActivityCursor validCursor(ActivityTypeFilter scope) {
        return new ActivityCursor(NOW, VISIBILITY_SNAPSHOT, NOW, DEPLOYMENT_SORT_KEY, scope);
    }

    private static ActivityCursorCodec codec() {
        return new ActivityCursorCodec(KEY);
    }

    private static String unsignedLegacyCursor(boolean includeScope) {
        String value = NOW + "\n" + VISIBILITY_SNAPSHOT + "\n" + NOW + "\n" + DEPLOYMENT_SORT_KEY
                + (includeScope ? "\nALL" : "");
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static StoredActivity activity(String id, Instant occurredAt, String sortKey) {
        return new StoredActivity(new ActivityEventResponse(id, Type.DEPLOYMENT, "Deployment", "SUCCESS",
                Severity.INFO, occurredAt, "abcdef123456"), sortKey);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
