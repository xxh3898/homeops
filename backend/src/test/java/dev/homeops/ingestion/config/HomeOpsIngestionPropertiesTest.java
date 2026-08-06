package dev.homeops.ingestion.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HomeOpsIngestionPropertiesTest {
    @Test
    void should_leaveIngestionDisabled_when_secretIsEmpty() {
        assertThat(properties("").isConfigured()).isFalse();
        assertThat(properties(null).isConfigured()).isFalse();
    }

    @Test
    void should_enableIngestion_when_secretIsLowercaseHex() {
        assertThat(properties("a".repeat(64)).isConfigured()).isTrue();
    }

    @Test
    void should_rejectSecret_when_secretIsNotLowercaseHex() {
        assertThatThrownBy(() -> properties("A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ingestion shared secret must be empty or 64 lowercase hexadecimal characters");
        assertThatThrownBy(() -> properties("a".repeat(63)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_rejectInvalidAuthenticationWindows_when_durationIsNotAllowed() {
        assertThatThrownBy(() -> new HomeOpsIngestionProperties("a".repeat(64), Duration.ZERO,
                Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ingestion maximum request age must be positive");
        assertThatThrownBy(() -> new HomeOpsIngestionProperties("a".repeat(64), Duration.ofMinutes(5),
                Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ingestion allowed future skew must not be negative");
        assertThat(new HomeOpsIngestionProperties("a".repeat(64), Duration.ofMinutes(5), Duration.ZERO)
                .isConfigured()).isTrue();
    }

    private static HomeOpsIngestionProperties properties(String sharedSecret) {
        return new HomeOpsIngestionProperties(sharedSecret, Duration.ofMinutes(5), Duration.ofMinutes(1));
    }
}
