package dev.homeops.notification.discord;

import dev.homeops.notification.NotificationField;
import dev.homeops.notification.NotificationPayload;
import dev.homeops.notification.NotificationSeverity;
import dev.homeops.notification.config.HomeOpsNotificationProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class DiscordMessageRenderer {
    private final ObjectMapper mapper;
    private final int maximumBytes;

    @Autowired
    DiscordMessageRenderer(ObjectMapper mapper, HomeOpsNotificationProperties properties) {
        this(mapper, properties.payloadMaxBytes());
    }

    DiscordMessageRenderer(ObjectMapper mapper, int maximumBytes) {
        this.mapper = mapper;
        this.maximumBytes = maximumBytes;
    }

    byte[] render(NotificationPayload payload, NotificationSeverity severity) {
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", payload.title());
        embed.put("description", payload.summary());
        embed.put("color", color(severity));
        embed.put("timestamp", payload.timestamp().toString());
        embed.put("footer", Map.of("text", "Event " + payload.eventCode()));
        if (!payload.fields().isEmpty()) {
            List<Map<String, Object>> fields = new ArrayList<>();
            for (NotificationField field : payload.fields()) {
                fields.add(Map.of(
                        "name", field.name(),
                        "value", field.value(),
                        "inline", field.inline()));
            }
            embed.put("fields", fields);
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("allowed_mentions", Map.of("parse", List.of()));
        message.put("embeds", List.of(embed));
        try {
            byte[] rendered = mapper.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
            if (rendered.length > maximumBytes) {
                throw new IllegalArgumentException("Discord payload exceeds the serialized byte limit");
            }
            return rendered;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Discord payload could not be serialized");
        }
    }

    private static int color(NotificationSeverity severity) {
        return switch (severity) {
            case INFO -> 0x3498db;
            case WARNING -> 0xf1c40f;
            case CRITICAL -> 0xe74c3c;
            case RECOVERY -> 0x2ecc71;
        };
    }
}
