package dev.homeops.ingestion.persistence;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class PostgresqlIngestionTestDatabase implements AutoCloseable {
    private final String schema;
    private final DriverManagerDataSource adminDataSource;
    private final DriverManagerDataSource schemaDataSource;

    private PostgresqlIngestionTestDatabase() {
        schema = "ingestion_" + UUID.randomUUID().toString().replace("-", "");
        adminDataSource = new DriverManagerDataSource(
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_URL"),
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_USERNAME"),
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_PASSWORD"));
        new JdbcTemplate(adminDataSource).execute("CREATE SCHEMA " + schema);

        String baseUrl = requiredEnvironment("HOMEOPS_TEST_POSTGRES_URL");
        String separator = baseUrl.contains("?") ? "&" : "?";
        schemaDataSource = new DriverManagerDataSource(
                baseUrl + separator + "currentSchema=" + schema,
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_USERNAME"),
                requiredEnvironment("HOMEOPS_TEST_POSTGRES_PASSWORD"));
    }

    static PostgresqlIngestionTestDatabase create() {
        return new PostgresqlIngestionTestDatabase();
    }

    Flyway migrateTo(String version) {
        Flyway flyway = Flyway.configure()
                .dataSource(schemaDataSource)
                .locations("classpath:db/migration")
                .defaultSchema(schema)
                .schemas(schema)
                .target(version)
                .load();
        flyway.migrate();
        return flyway;
    }

    Flyway migrateToCurrent() {
        Flyway flyway = Flyway.configure()
                .dataSource(schemaDataSource)
                .locations("classpath:db/migration")
                .defaultSchema(schema)
                .schemas(schema)
                .load();
        flyway.migrate();
        return flyway;
    }

    JdbcTemplate jdbc() {
        return new JdbcTemplate(schemaDataSource);
    }

    @Override
    public void close() {
        new JdbcTemplate(adminDataSource).execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured for this test");
        }
        return value;
    }
}
