package dev.homeops.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("homeops.notifications.container")
public record ContainerNotificationProperties(
        Duration failureAfter,
        Duration realertCooldown) {
    private static final Duration MINIMUM_FAILURE_AFTER = Duration.ofSeconds(5);
    private static final Duration MAXIMUM_FAILURE_AFTER = Duration.ofMinutes(10);
    private static final Duration MINIMUM_REALERT_COOLDOWN = Duration.ofSeconds(30);
    private static final Duration MAXIMUM_REALERT_COOLDOWN = Duration.ofHours(1);

    public ContainerNotificationProperties {
        requireBounded(
                failureAfter,
                MINIMUM_FAILURE_AFTER,
                MAXIMUM_FAILURE_AFTER,
                "Container notification failure threshold");
        requireBounded(
                realertCooldown,
                MINIMUM_REALERT_COOLDOWN,
                MAXIMUM_REALERT_COOLDOWN,
                "Container notification re-alert cooldown");
    }

    private static void requireBounded(
            Duration value,
            Duration minimum,
            Duration maximum,
            String label) {
        if (value == null
                || value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(label + " is outside its supported bound");
        }
    }
}
