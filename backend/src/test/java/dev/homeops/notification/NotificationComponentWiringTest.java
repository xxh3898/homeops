package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homeops.notification.config.HomeOpsNotificationProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

class NotificationComponentWiringTest {

    @Test
    void should_selectProductionConstructors_when_notificationComponentsAreRegistered() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(HomeOpsNotificationProperties.class,
                    NotificationComponentWiringTest::properties);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(NotificationBackoffPolicy.class, NotificationPayloadCodec.class);

            context.refresh();

            assertThat(context.getBean(NotificationBackoffPolicy.class)).isNotNull();
            assertThat(context.getBean(NotificationPayloadCodec.class)).isNotNull();
        }
    }

    private static HomeOpsNotificationProperties properties() {
        return new HomeOpsNotificationProperties(
                false, null,
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofSeconds(1), 10, 6,
                Duration.ofSeconds(5), Duration.ofMinutes(15),
                65_536, 8_192, Duration.ofDays(30), Duration.ofDays(90));
    }
}
