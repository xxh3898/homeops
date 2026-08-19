package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class NotificationMigrationSourceTest {

    @Test
    void should_keepV1MigrationChecksumUnchanged_when_notificationFoundationIsAdded() throws Exception {
        try (InputStream source = getClass().getResourceAsStream(
                "/db/migration/V1__create_homeops_schema.sql")) {
            assertThat(source).isNotNull();
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source.readAllBytes()));

            assertThat(digest).isEqualTo(
                    "4174cf26e762b49932b075b2ecc7a24dea7c755a308ee2394f97deda87b3a228");
        }
    }
}
