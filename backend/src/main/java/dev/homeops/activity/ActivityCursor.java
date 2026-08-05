package dev.homeops.activity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

record ActivityCursor(Instant snapshotAt, Instant occurredAt, String sortKey) {
    private static final String SEPARATOR = "\n";

    static ActivityCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > 512) {
            throw new InvalidActivityCursorException();
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = value.split(SEPARATOR, 3);
            if (parts.length != 3 || parts[2].isBlank() || parts[2].length() > 160) {
                throw new InvalidActivityCursorException();
            }
            return new ActivityCursor(Instant.parse(parts[0]), Instant.parse(parts[1]), parts[2]);
        } catch (IllegalArgumentException exception) {
            throw new InvalidActivityCursorException();
        }
    }

    String encode() {
        String value = snapshotAt + SEPARATOR + occurredAt + SEPARATOR + sortKey;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
