package dev.homeops.ops;

import org.flywaydb.core.Flyway;

public final class MigrationMain {

    private MigrationMain() {
    }

    public static void main(String[] args) {
        String url = requiredEnvironment("SPRING_DATASOURCE_URL");
        String username = requiredEnvironment("SPRING_DATASOURCE_USERNAME");
        String password = requiredEnvironment("SPRING_DATASOURCE_PASSWORD");

        var result = Flyway.configure()
                .dataSource(url, username, password)
                .failOnMissingLocations(true)
                .load()
                .migrate();
        System.out.printf(
                "HomeOps migration completed: %d migration(s)%n",
                result.migrationsExecuted);
    }

    static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required migration environment is missing: " + name);
        }
        return value;
    }
}

