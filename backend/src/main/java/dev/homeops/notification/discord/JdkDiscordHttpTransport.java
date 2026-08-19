package dev.homeops.notification.discord;

import dev.homeops.notification.config.HomeOpsNotificationProperties;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class JdkDiscordHttpTransport implements DiscordHttpTransport {
    private final HttpClient client;

    @Autowired
    JdkDiscordHttpTransport(HomeOpsNotificationProperties properties) {
        this(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    JdkDiscordHttpTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public DiscordHttpResponse send(
            DiscordWebhookEndpoint endpoint,
            byte[] body,
            Duration requestTimeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint.executionUri())
                .header("Content-Type", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<java.io.InputStream> response = client.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        return new DiscordHttpResponse(
                response.statusCode(), response.headers().map(), response.body());
    }

    HttpClient client() {
        return client;
    }
}
