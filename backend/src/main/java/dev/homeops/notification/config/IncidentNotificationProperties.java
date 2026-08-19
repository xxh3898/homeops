package dev.homeops.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("homeops.notifications.incident")
public record IncidentNotificationProperties(Duration escalationAfter) {
    private static final Duration MINIMUM_ESCALATION_AFTER = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_ESCALATION_AFTER = Duration.ofHours(24);

    public IncidentNotificationProperties {
        if (escalationAfter == null
                || escalationAfter.compareTo(MINIMUM_ESCALATION_AFTER) < 0
                || escalationAfter.compareTo(MAXIMUM_ESCALATION_AFTER) > 0) {
            throw new IllegalArgumentException(
                    "Incident notification escalation threshold must be between 5 minutes and 24 hours");
        }
    }
}
