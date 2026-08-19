package dev.homeops.notification.discord;

import java.io.IOException;
import java.time.Duration;

interface DiscordHttpTransport {
    DiscordHttpResponse send(
            DiscordWebhookEndpoint endpoint,
            byte[] body,
            Duration requestTimeout) throws IOException, InterruptedException;
}
