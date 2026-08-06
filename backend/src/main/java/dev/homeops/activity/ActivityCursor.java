package dev.homeops.activity;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;

record ActivityCursor(Instant snapshotAt, String visibilitySnapshot, Instant occurredAt, String sortKey) {
    private static final String SEPARATOR = "\n";
    private static final int MAXIMUM_ENCODED_LENGTH = 4096;
    private static final int MAXIMUM_VISIBILITY_SNAPSHOT_LENGTH = 2048;
    private static final String VISIBILITY_SNAPSHOT_PATTERN = "[0-9]+:[0-9]+:(?:[0-9]+(?:,[0-9]+)*)?";

    static ActivityCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAXIMUM_ENCODED_LENGTH) {
            throw new InvalidActivityCursorException();
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = value.split(SEPARATOR, 4);
            if (parts.length != 4 || parts[1].length() > MAXIMUM_VISIBILITY_SNAPSHOT_LENGTH
                    || !parts[1].matches(VISIBILITY_SNAPSHOT_PATTERN)
                    || parts[3].isBlank() || parts[3].length() > 160) {
                throw new InvalidActivityCursorException();
            }
            return new ActivityCursor(Instant.parse(parts[0]), parts[1], Instant.parse(parts[2]), parts[3]);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new InvalidActivityCursorException();
        }
    }

    String encode() {
        String value = snapshotAt + SEPARATOR + visibilitySnapshot + SEPARATOR + occurredAt + SEPARATOR + sortKey;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
