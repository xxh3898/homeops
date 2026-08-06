package dev.homeops.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PostgresqlTimestampRangeTest {

    @Test
    void should_acceptTimestamp_when_valueIsAtPostgresqlMinimum() {
        assertThat(PostgresqlTimestampRange.isSupported(Instant.ofEpochSecond(-210_866_803_200L))).isTrue();
    }

    @Test
    void should_rejectTimestamp_when_valueIsAtPostgresqlEnd() {
        assertThat(PostgresqlTimestampRange.isSupported(Instant.ofEpochSecond(9_224_318_016_000L))).isFalse();
    }
}
