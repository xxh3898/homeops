package dev.homeops.notification.discord;

import java.util.List;
import java.util.Map;

record DiscordHttpResponse(
        int statusCode,
        Map<String, List<String>> headers,
        byte[] body,
        boolean bodyExceededLimit) {

    DiscordHttpResponse {
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    @Override
    public String toString() {
        return "DiscordHttpResponse[statusCode=" + statusCode
                + ", headers=redacted, body=redacted, bodyExceededLimit="
                + bodyExceededLimit + "]";
    }
}
