package dev.homeops.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("homeops.notifications.agent")
public record AgentNotificationProperties(Duration freshnessCheckDelay) {
    private static final Duration MINIMUM_DELAY = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_DELAY = Duration.ofMinutes(1);

    public AgentNotificationProperties {
        if (freshnessCheckDelay == null
                || freshnessCheckDelay.compareTo(MINIMUM_DELAY) < 0
                || freshnessCheckDelay.compareTo(MAXIMUM_DELAY) > 0) {
            throw new IllegalArgumentException(
                    "Agent freshness check delay must be between 1 second and 1 minute");
        }
    }
}
