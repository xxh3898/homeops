package dev.homeops.notification.config;

import org.springframework.stereotype.Component;

@Component
final class NotificationConfigurationGuard {

    NotificationConfigurationGuard(HomeOpsNotificationProperties properties) {
        properties.validateWebhookConfiguration();
    }
}
