package dev.homeops.notification;

import dev.homeops.notification.config.HomeOpsNotificationProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class NotificationPayloadCodec {
    private final ObjectMapper mapper;
    private final int maximumBytes;

    @Autowired
    public NotificationPayloadCodec(ObjectMapper mapper, HomeOpsNotificationProperties properties) {
        this(mapper, properties.payloadMaxBytes());
    }

    NotificationPayloadCodec(ObjectMapper mapper, int maximumBytes) {
        this.mapper = mapper;
        this.maximumBytes = maximumBytes;
    }

    public String encode(NotificationPayload payload) {
        try {
            String json = mapper.writeValueAsString(new StoredPayload(
                    payload.eventCode(), payload.title(), payload.summary(),
                    payload.fields(), payload.timestamp().toString()));
            if (json.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
                throw new IllegalArgumentException("Notification payload exceeds the serialized byte limit");
            }
            return json;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Notification payload could not be serialized");
        }
    }

    public NotificationPayload decode(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException("Stored notification payload is invalid");
        }
        try {
            StoredPayload stored = mapper.readValue(json, StoredPayload.class);
            return new NotificationPayload(
                    stored.eventCode(), stored.title(), stored.summary(),
                    stored.fields(), Instant.parse(stored.timestamp()));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Stored notification payload is invalid");
        }
    }

    private record StoredPayload(
            String eventCode,
            String title,
            String summary,
            List<NotificationField> fields,
            String timestamp) { }
}
