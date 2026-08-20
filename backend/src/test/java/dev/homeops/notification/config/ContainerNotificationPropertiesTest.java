package dev.homeops.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ContainerNotificationPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void should_bindDefaults_when_supportedValuesAreConfigured() {
        contextRunner.withPropertyValues(
                        "homeops.notifications.container.failure-after=30s",
                        "homeops.notifications.container.realert-cooldown=5m")
                .run(context -> {
                    ContainerNotificationProperties properties =
                            context.getBean(ContainerNotificationProperties.class);
                    assertThat(properties.failureAfter()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(properties.realertCooldown()).isEqualTo(Duration.ofMinutes(5));
                });
    }

    @Test
    void should_acceptInclusiveBounds_when_valuesUseSupportedEdges() {
        assertThat(new ContainerNotificationProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(30)).failureAfter())
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(new ContainerNotificationProperties(
                Duration.ofMinutes(10), Duration.ofHours(1)).realertCooldown())
                .isEqualTo(Duration.ofHours(1));
    }

    @Test
    void should_rejectFailureThreshold_when_valueIsOutsideBound() {
        assertThatThrownBy(() -> new ContainerNotificationProperties(
                Duration.ofSeconds(4), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure threshold");
        assertThatThrownBy(() -> new ContainerNotificationProperties(
                Duration.ofMinutes(11), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure threshold");
    }

    @Test
    void should_rejectCooldown_when_valueIsOutsideBound() {
        assertThatThrownBy(() -> new ContainerNotificationProperties(
                Duration.ofSeconds(30), Duration.ofSeconds(29)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("re-alert cooldown");
        assertThatThrownBy(() -> new ContainerNotificationProperties(
                Duration.ofSeconds(30), Duration.ofHours(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("re-alert cooldown");
    }

    @EnableConfigurationProperties(ContainerNotificationProperties.class)
    static class TestConfiguration { }
}
