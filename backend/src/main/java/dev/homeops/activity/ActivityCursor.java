package dev.homeops.activity;

import dev.homeops.common.PostgresqlTimestampRange;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.regex.Pattern;

record ActivityCursor(
        Instant snapshotAt,
        String visibilitySnapshot,
        Instant occurredAt,
        String sortKey,
        ActivityTypeFilter scope) {
    private static final String SEPARATOR = "\n";
    private static final int MAXIMUM_VISIBILITY_SNAPSHOT_LENGTH = 2048;
    private static final String VISIBILITY_SNAPSHOT_PATTERN = "[0-9]+:[0-9]+:(?:[0-9]+(?:,[0-9]+)*)?";
    private static final Pattern SORT_KEY_PATTERN = Pattern.compile(
            "^(?:DEPLOYMENT|BACKUP|INCIDENT_OPEN|INCIDENT_RECOVERY|AGENT|CONTAINER_ACTION):"
                    + "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final BigInteger MAXIMUM_XID = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    static ActivityCursor parsePayload(String value) {
        try {
            String[] parts = value.split(SEPARATOR, -1);
            if (parts.length != 5
                    || parts[1].length() > MAXIMUM_VISIBILITY_SNAPSHOT_LENGTH
                    || !isValidVisibilitySnapshot(parts[1])
                    || !isValidSortKey(parts[3])) {
                throw new InvalidActivityCursorException();
            }
            return new ActivityCursor(parsePostgresqlTimestamp(parts[0]), parts[1], parsePostgresqlTimestamp(parts[2]),
                    parts[3], parseScope(parts[4]));
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new InvalidActivityCursorException();
        }
    }

    String payload() {
        return snapshotAt + SEPARATOR + visibilitySnapshot + SEPARATOR + occurredAt + SEPARATOR + sortKey
                + SEPARATOR + scope;
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

    private static boolean isValidSortKey(String sortKey) {
        return SORT_KEY_PATTERN.matcher(sortKey).matches();
    }

    private static ActivityTypeFilter parseScope(String value) {
        try {
            return ActivityTypeFilter.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidActivityCursorException();
        }
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
