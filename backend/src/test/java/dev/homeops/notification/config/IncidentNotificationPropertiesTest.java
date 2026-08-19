package dev.homeops.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class IncidentNotificationPropertiesTest {
    @Test
    void should_bindDefaultContract_when_configurationUsesFifteenMinutes() {
        new ApplicationContextRunner()
                .withUserConfiguration(IncidentPropertiesConfiguration.class)
                .withPropertyValues("homeops.notifications.incident.escalation-after=15m")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IncidentNotificationProperties.class).escalationAfter())
                            .isEqualTo(Duration.ofMinutes(15));
                });
    }

    @Test
    void should_acceptConfiguration_when_thresholdIsWithinInclusiveBounds() {
        assertThat(new IncidentNotificationProperties(Duration.ofMinutes(5)).escalationAfter())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(new IncidentNotificationProperties(Duration.ofHours(24)).escalationAfter())
                .isEqualTo(Duration.ofHours(24));
    }

    @Test
    void should_rejectConfiguration_when_thresholdIsBelowMinimum() {
        assertThatThrownBy(() -> new IncidentNotificationProperties(Duration.ofMinutes(4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 5 minutes and 24 hours");
    }

    @Test
    void should_rejectConfiguration_when_thresholdExceedsMaximum() {
        assertThatThrownBy(() -> new IncidentNotificationProperties(Duration.ofHours(25)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 5 minutes and 24 hours");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IncidentNotificationProperties.class)
    static class IncidentPropertiesConfiguration { }
}
