package dev.homeops.common;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Canonical PostgreSQL/JDBC timestamptz representation at microsecond precision. */
public final class PostgresqlTimestamp {
    private PostgresqlTimestamp() { }

    public static boolean isSupported(Instant timestamp) {
        if (!PostgresqlTimestampRange.isSupported(timestamp)) return false;
        return PostgresqlTimestampRange.isSupported(roundToMicroseconds(timestamp));
    }

    public static Instant canonicalize(Instant timestamp) {
        if (timestamp == null) return null;
        if (!isSupported(timestamp)) {
            throw new IllegalArgumentException("Timestamp is outside PostgreSQL's supported range");
        }
        return roundToMicroseconds(timestamp);
    }

    public static Timestamp toTimestamp(Instant timestamp) {
        Instant canonical = canonicalize(timestamp);
        return canonical == null ? null : Timestamp.from(canonical);
    }

    private static Instant roundToMicroseconds(Instant timestamp) {
        return timestamp.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
    }
}
