package dev.homeops.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PostgresqlTimestampTest {
    @ParameterizedTest
    @MethodSource("canonicalInstants")
    void should_canonicalizeAtPostgresqlJdbcPrecision(String input, String expected) {
        assertThat(PostgresqlTimestamp.canonicalize(Instant.parse(input))).isEqualTo(Instant.parse(expected));
    }

    @ParameterizedTest
    @MethodSource("nullableInstants")
    void should_preserveNullForNullableTimestamp(Instant input) {
        assertThat(PostgresqlTimestamp.canonicalize(input)).isNull();
        assertThat(PostgresqlTimestamp.toTimestamp(input)).isNull();
    }

    @org.junit.jupiter.api.Test
    void should_rejectValueWhoseRoundingCarryLeavesPostgresqlRange() {
        Instant carryPastEnd = PostgresqlTimestampRange.endExclusive().minusNanos(1);

        assertThat(PostgresqlTimestamp.isSupported(carryPastEnd)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> PostgresqlTimestamp.canonicalize(carryPastEnd));
    }

    @org.junit.jupiter.api.Test
    void should_acceptPostgresqlRangeBoundariesWhenCanonicalValueIsSupported() {
        assertThat(PostgresqlTimestamp.canonicalize(PostgresqlTimestampRange.minimum()))
                .isEqualTo(PostgresqlTimestampRange.minimum());
        assertThat(PostgresqlTimestamp.canonicalize(PostgresqlTimestampRange.endExclusive().minusNanos(1_000)))
                .isEqualTo(PostgresqlTimestampRange.endExclusive().minusNanos(1_000));
    }

    private static Stream<Arguments> canonicalInstants() {
        return Stream.of(
                Arguments.of("2026-01-01T00:00:00.123456000Z", "2026-01-01T00:00:00.123456Z"),
                Arguments.of("2026-01-01T00:00:00.123456499Z", "2026-01-01T00:00:00.123456Z"),
                Arguments.of("2026-01-01T00:00:00.123456500Z", "2026-01-01T00:00:00.123457Z"),
                Arguments.of("2026-01-01T00:00:00.123456789Z", "2026-01-01T00:00:00.123457Z"),
                Arguments.of("2026-01-01T00:00:00.999999499Z", "2026-01-01T00:00:00.999999Z"),
                Arguments.of("2026-01-01T00:00:00.999999500Z", "2026-01-01T00:00:01Z"),
                Arguments.of("1969-12-31T23:59:59.123456500Z", "1969-12-31T23:59:59.123457Z"));
    }

    private static Stream<Instant> nullableInstants() {
        return Stream.of((Instant) null);
    }
}
