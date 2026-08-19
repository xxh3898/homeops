package dev.homeops.notification.discord;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homeops.notification.config.HomeOpsNotificationProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

class DiscordComponentWiringTest {

    @Test
    void should_selectProductionConstructors_when_discordComponentsAreRegistered() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(HomeOpsNotificationProperties.class,
                    DiscordComponentWiringTest::properties);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(JdkDiscordHttpTransport.class, DiscordMessageRenderer.class);

            context.refresh();

            assertThat(context.getBean(JdkDiscordHttpTransport.class)).isNotNull();
            assertThat(context.getBean(DiscordMessageRenderer.class)).isNotNull();
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
