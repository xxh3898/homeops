package dev.homeops.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AgentNotificationPropertiesTest {
    @Test
    void should_bindDefaultContract_when_configurationUsesFiveSeconds() {
        new ApplicationContextRunner()
                .withUserConfiguration(AgentPropertiesConfiguration.class)
                .withPropertyValues("homeops.notifications.agent.freshness-check-delay=5s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AgentNotificationProperties.class).freshnessCheckDelay())
                            .isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Test
    void should_acceptConfiguration_when_delayIsWithinInclusiveBounds() {
        assertThat(new AgentNotificationProperties(Duration.ofSeconds(1)).freshnessCheckDelay())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(new AgentNotificationProperties(Duration.ofMinutes(1)).freshnessCheckDelay())
                .isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void should_rejectConfiguration_when_delayIsOutsideBounds() {
        assertThatThrownBy(() -> new AgentNotificationProperties(Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 second and 1 minute");
        assertThatThrownBy(() -> new AgentNotificationProperties(Duration.ofSeconds(61)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 second and 1 minute");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentNotificationProperties.class)
    static class AgentPropertiesConfiguration { }
}
