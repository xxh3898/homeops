package dev.homeops.activity;

import dev.homeops.common.PostgresqlTimestampRange;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;

record ActivityCursor(Instant snapshotAt, String visibilitySnapshot, Instant occurredAt, String sortKey) {
    private static final String SEPARATOR = "\n";
    private static final int MAXIMUM_ENCODED_LENGTH = 4096;
    private static final int MAXIMUM_VISIBILITY_SNAPSHOT_LENGTH = 2048;
    private static final String VISIBILITY_SNAPSHOT_PATTERN = "[0-9]+:[0-9]+:(?:[0-9]+(?:,[0-9]+)*)?";
    private static final BigInteger MAXIMUM_XID = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    static ActivityCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAXIMUM_ENCODED_LENGTH) {
            throw new InvalidActivityCursorException();
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = value.split(SEPARATOR, 4);
            if (parts.length != 4 || parts[1].length() > MAXIMUM_VISIBILITY_SNAPSHOT_LENGTH
                    || !isValidVisibilitySnapshot(parts[1])
                    || parts[3].isBlank() || parts[3].length() > 160) {
                throw new InvalidActivityCursorException();
            }
            return new ActivityCursor(parsePostgresqlTimestamp(parts[0]), parts[1], parsePostgresqlTimestamp(parts[2]),
                    parts[3]);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new InvalidActivityCursorException();
        }
    }

    String encode() {
        String value = snapshotAt + SEPARATOR + visibilitySnapshot + SEPARATOR + occurredAt + SEPARATOR + sortKey;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isValidVisibilitySnapshot(String snapshot) {
        if (!snapshot.matches(VISIBILITY_SNAPSHOT_PATTERN)) {
            return false;
        }
        String[] parts = snapshot.split(":", -1);
        BigInteger xmin = parseXid(parts[0]);
        BigInteger xmax = parseXid(parts[1]);
        if (xmin == null || xmax == null || xmax.compareTo(xmin) < 0) {
            return false;
        }
        BigInteger previous = null;
        for (String xip : parts[2].split(",")) {
            if (xip.isEmpty()) {
                continue;
            }
            BigInteger value = parseXid(xip);
            if (value == null || value.compareTo(xmin) < 0 || value.compareTo(xmax) >= 0
                    || (previous != null && value.compareTo(previous) < 0)) {
                return false;
            }
            previous = value;
        }
        return true;
    }

    private static BigInteger parseXid(String value) {
        BigInteger xid = new BigInteger(value);
        return xid.signum() > 0 && xid.compareTo(MAXIMUM_XID) <= 0 ? xid : null;
    }

    private static Instant parsePostgresqlTimestamp(String value) {
        Instant timestamp = Instant.parse(value);
        if (!PostgresqlTimestampRange.isSupported(timestamp)) {
            throw new InvalidActivityCursorException();
        }
        return timestamp;
    }
}
