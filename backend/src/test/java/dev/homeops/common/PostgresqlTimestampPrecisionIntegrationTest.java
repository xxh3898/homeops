package dev.homeops.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "HOMEOPS_TEST_POSTGRES_URL", matches = ".+")
class PostgresqlTimestampPrecisionIntegrationTest {
    @Test
    void should_characterizeTimestampPrecisionThroughCurrentJdbcDriver() throws Exception {
        Map<String, Instant> inputs = new LinkedHashMap<>();
        inputs.put("exact-microsecond", Instant.parse("2026-01-01T00:00:00.123456000Z"));
        inputs.put("discard-499ns", Instant.parse("2026-01-01T00:00:00.123456499Z"));
        inputs.put("half-microsecond", Instant.parse("2026-01-01T00:00:00.123456500Z"));
        inputs.put("round-789ns", Instant.parse("2026-01-01T00:00:00.123456789Z"));
        inputs.put("before-carry", Instant.parse("2026-01-01T00:00:00.999999499Z"));
        inputs.put("carry", Instant.parse("2026-01-01T00:00:00.999999500Z"));
        inputs.put("previous-second-carry", Instant.parse("2025-12-31T23:59:59.999999500Z"));
        inputs.put("postgresql-minimum", PostgresqlTimestampRange.minimum());
        inputs.put("postgresql-maximum", PostgresqlTimestampRange.endExclusive().minusNanos(1_000));

        var dataSource = new DriverManagerDataSource(
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_URL"),
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_USERNAME"),
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_PASSWORD"));
        Map<String, Instant> observed = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO timestamp_precision_probe (label, value) VALUES (?, ?)");
                PreparedStatement select = connection.prepareStatement(
                        "SELECT value FROM timestamp_precision_probe WHERE label = ?")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE timestamp_precision_probe (label text, value timestamptz)");
            }
            for (Map.Entry<String, Instant> input : inputs.entrySet()) {
                insert.setString(1, input.getKey());
                insert.setTimestamp(2, Timestamp.from(input.getValue()));
                insert.executeUpdate();
                select.setString(1, input.getKey());
                try (ResultSet result = select.executeQuery()) {
                    result.next();
                    observed.put(input.getKey(), result.getTimestamp(1).toInstant());
                }
            }
        }
        Map<String, Instant> expected = new LinkedHashMap<>();
        expected.put("exact-microsecond", Instant.parse("2026-01-01T00:00:00.123456Z"));
        expected.put("discard-499ns", Instant.parse("2026-01-01T00:00:00.123456Z"));
        expected.put("half-microsecond", Instant.parse("2026-01-01T00:00:00.123457Z"));
        expected.put("round-789ns", Instant.parse("2026-01-01T00:00:00.123457Z"));
        expected.put("before-carry", Instant.parse("2026-01-01T00:00:00.999999Z"));
        expected.put("carry", Instant.parse("2026-01-01T00:00:01Z"));
        expected.put("previous-second-carry", Instant.parse("2026-01-01T00:00:00Z"));
        expected.put("postgresql-minimum", PostgresqlTimestampRange.minimum());
        expected.put("postgresql-maximum", PostgresqlTimestampRange.endExclusive().minusNanos(1_000));

        assertThat(observed).containsExactlyEntriesOf(expected);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured for this test");
        return value;
    }
}
