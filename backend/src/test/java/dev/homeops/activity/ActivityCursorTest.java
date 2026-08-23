package dev.homeops.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ActivityCursorTest {
    private static final Instant SNAPSHOT_AT = Instant.parse("2026-08-07T00:00:00Z");
    private static final String VISIBILITY_SNAPSHOT = "100:200:150";
    private static final String UUID = "10000000-0000-0000-0000-000000000001";

    @ParameterizedTest
    @MethodSource("validSortKeys")
    void should_decodeAndEncodeCursor_when_sortKeyUsesServerGeneratedFormat(String sortKey) {
        ActivityCursor cursor = new ActivityCursor(SNAPSHOT_AT, VISIBILITY_SNAPSHOT, SNAPSHOT_AT, sortKey);

        assertThat(ActivityCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @ParameterizedTest
    @MethodSource("invalidSortKeys")
    void should_rejectCursor_when_sortKeyIsNotServerGeneratedFormat(String sortKey) {
        assertThatThrownBy(() -> ActivityCursor.decode(cursorWithSortKey(sortKey)))
                .isInstanceOf(InvalidActivityCursorException.class);
    }

    private static Stream<String> validSortKeys() {
        return Stream.of(
                "DEPLOYMENT:" + UUID,
                "BACKUP:" + UUID,
                "INCIDENT_OPEN:" + UUID,
                "INCIDENT_RECOVERY:" + UUID,
                "AGENT:" + UUID,
                "CONTAINER_ACTION:" + UUID);
    }

    private static Stream<String> invalidSortKeys() {
        return Stream.of(
                "DEPLOYMENT:" + UUID + "\u0000",
                "DEPLOYMENT:" + UUID + "\nBACKUP:" + UUID,
                "UNKNOWN:" + UUID,
                "DEPLOYMENT:not-a-uuid",
                "DEPLOYMENT:10000000-0000-0000-0000-00000000000z",
                "DEPLOYMENT:" + "0".repeat(161));
    }

    private static String cursorWithSortKey(String sortKey) {
        String value = SNAPSHOT_AT + "\n" + VISIBILITY_SNAPSHOT + "\n" + SNAPSHOT_AT + "\n" + sortKey;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
