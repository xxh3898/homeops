package dev.homeops.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class HomeOpsNotificationPropertiesTest {
    private static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/"
            + "a".repeat(64);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    NotificationPropertiesConfiguration.class,
                    NotificationConfigurationGuard.class)
            .withPropertyValues(
                    "homeops.notifications.enabled=false",
                    "homeops.notifications.connect-timeout=3s",
                    "homeops.notifications.request-timeout=5s",
                    "homeops.notifications.lease-duration=30s",
                    "homeops.notifications.poll-delay=1s",
                    "homeops.notifications.batch-size=10",
                    "homeops.notifications.max-attempts=6",
                    "homeops.notifications.initial-backoff=5s",
                    "homeops.notifications.max-backoff=15m",
                    "homeops.notifications.response-max-bytes=65536",
                    "homeops.notifications.payload-max-bytes=8192",
                    "homeops.notifications.sent-retention=30d",
                    "homeops.notifications.failed-retention=90d");

    @Test
    void should_startWithoutWebhook_when_notificationsAreDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            HomeOpsNotificationProperties properties = context.getBean(HomeOpsNotificationProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.discordWebhookEndpoint()).isEmpty();
        });
    }

    @Test
    void should_failStartupWithoutWebhook_when_notificationsAreEnabled() {
        contextRunner.withPropertyValues("homeops.notifications.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void should_bindStrictWebhookWithoutExposingToken_when_configurationIsValid() {
        contextRunner.withPropertyValues(
                "homeops.notifications.enabled=true",
                "homeops.notifications.discord-webhook-url=" + WEBHOOK)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HomeOpsNotificationProperties properties = context.getBean(HomeOpsNotificationProperties.class);
                    assertThat(properties.discordWebhookEndpoint()).isPresent();
                    assertThat(properties.toString()).doesNotContain("a".repeat(32));
                    assertThat(properties.discordWebhookEndpoint().orElseThrow().toString())
                            .doesNotContain("a".repeat(32));
                });
    }

    @Test
    void should_failStartup_when_nonBlankWebhookIsInvalidEvenWhileDisabled() {
        String secretMarker = "synthetic-secret-marker";
        contextRunner.withPropertyValues(
                "homeops.notifications.discord-webhook-url=https://discord.com/api/webhooks/"
                        + "123456789012345678/" + secretMarker + "?unexpected=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    while (failure != null) {
                        if (failure.getMessage() != null) {
                            assertThat(failure.getMessage()).doesNotContain(secretMarker);
                        }
                        failure = failure.getCause();
                    }
                });
    }

    @Test
    void should_rejectBounds_when_workerConfigurationExceedsContract() {
        assertThatThrownBy(() -> properties(false, null, Duration.ofSeconds(16),
                Duration.ofSeconds(16), Duration.ofSeconds(30), 10, 6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect timeout");
        assertThatThrownBy(() -> properties(false, null, Duration.ofSeconds(3),
                Duration.ofSeconds(5), Duration.ofSeconds(5), 10, 6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease duration");
        assertThatThrownBy(() -> properties(false, null, Duration.ofSeconds(3),
                Duration.ofSeconds(5), Duration.ofSeconds(30), 11, 6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch size");
        assertThatThrownBy(() -> properties(false, null, Duration.ofSeconds(3),
                Duration.ofSeconds(5), Duration.ofSeconds(30), 10, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum attempts");
    }

    static HomeOpsNotificationProperties properties(boolean enabled, String webhook) {
        return properties(enabled, webhook, Duration.ofSeconds(3), Duration.ofSeconds(5),
                Duration.ofSeconds(30), 10, 6);
    }

    private static HomeOpsNotificationProperties properties(
            boolean enabled,
            String webhook,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration leaseDuration,
            int batchSize,
            int maxAttempts) {
        return new HomeOpsNotificationProperties(
                enabled, webhook, connectTimeout, requestTimeout, leaseDuration,
                Duration.ofSeconds(1), batchSize, maxAttempts,
                Duration.ofSeconds(5), Duration.ofMinutes(15),
                65_536, 8_192, Duration.ofDays(30), Duration.ofDays(90));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HomeOpsNotificationProperties.class)
    static class NotificationPropertiesConfiguration { }
}
