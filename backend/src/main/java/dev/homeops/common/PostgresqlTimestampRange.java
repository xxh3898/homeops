package dev.homeops.common;

import java.time.Instant;

public final class PostgresqlTimestampRange {
    private static final Instant MIN_TIMESTAMP = Instant.ofEpochSecond(-210_866_803_200L);
    private static final Instant END_TIMESTAMP = Instant.ofEpochSecond(9_224_318_016_000L);

    private PostgresqlTimestampRange() { }

    public static boolean isSupported(Instant timestamp) {
        return timestamp != null && !timestamp.isBefore(MIN_TIMESTAMP) && timestamp.isBefore(END_TIMESTAMP);
    }
}
