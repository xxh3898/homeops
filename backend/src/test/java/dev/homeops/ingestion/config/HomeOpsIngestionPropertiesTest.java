package dev.homeops.ingestion.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class HomeOpsIngestionPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(IngestionPropertiesConfiguration.class);

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
    void should_acceptAuthenticationWindows_when_valuesAreAtSupportedBoundaries() {
        assertThat(new HomeOpsIngestionProperties("a".repeat(64),
                HomeOpsIngestionProperties.MAXIMUM_REQUEST_AGE_LIMIT,
                HomeOpsIngestionProperties.ALLOWED_FUTURE_SKEW_LIMIT).isConfigured()).isTrue();
        assertThat(new HomeOpsIngestionProperties("a".repeat(64), Duration.ofMinutes(5), Duration.ZERO)
                .isConfigured()).isTrue();
    }

    @Test
    void should_rejectMaximumRequestAge_when_valueIsZeroNegativeOrAboveLimit() {
        assertThatThrownBy(() -> new HomeOpsIngestionProperties("a".repeat(64), Duration.ZERO,
                Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ingestion maximum request age must be positive");
        assertThatThrownBy(() -> new HomeOpsIngestionProperties("a".repeat(64), Duration.ofSeconds(-1),
                Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ingestion maximum request age must be positive");
        assertThatThrownBy(() -> new HomeOpsIngestionProperties("a".repeat(64),
                HomeOpsIngestionProperties.MAXIMUM_REQUEST_AGE_LIMIT.plusSeconds(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ingestion maximum request age must not exceed 24 hours");
    }

    @Test
    void should_rejectAllowedFutureSkew_when_valueIsNegativeOrAboveLimit() {
        assertThatThrownBy(() -> new HomeOpsIngestionProperties("a".repeat(64), Duration.ofMinutes(5),
                Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ingestion allowed future skew must not be negative");
        assertThatThrownBy(() -> new HomeOpsIngestionProperties("a".repeat(64), Duration.ofMinutes(5),
                HomeOpsIngestionProperties.ALLOWED_FUTURE_SKEW_LIMIT.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ingestion allowed future skew must not exceed 15 minutes");
    }

    @Test
    void should_failApplicationStartup_when_boundDurationExceedsSupportedLimit() {
        contextRunner.withPropertyValues(
                "homeops.ingestion.shared-secret=" + "a".repeat(64),
                "homeops.ingestion.maximum-request-age=PT40000000000000000S",
                "homeops.ingestion.allowed-future-skew=PT0S")
                .run(context -> assertThat(context).hasFailed());
    }

    private static HomeOpsIngestionProperties properties(String sharedSecret) {
        return new HomeOpsIngestionProperties(sharedSecret, Duration.ofMinutes(5), Duration.ofMinutes(1));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HomeOpsIngestionProperties.class)
    static class IngestionPropertiesConfiguration { }
}
