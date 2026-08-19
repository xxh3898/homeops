package dev.homeops.notification.discord;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

record DiscordHttpResponse(
        int statusCode,
        Map<String, List<String>> headers,
        InputStream body) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        body.close();
    }
}
