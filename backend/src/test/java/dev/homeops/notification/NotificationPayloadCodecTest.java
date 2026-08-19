package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NotificationPayloadCodecTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void should_roundTripTypedPayload_when_payloadIsWithinBounds() {
        NotificationPayloadCodec codec = new NotificationPayloadCodec(mapper, 8_192);
        NotificationPayload payload = payload("Operation completed", List.of(
                new NotificationField("Project", "homeops", true)));

        NotificationPayload decoded = codec.decode(codec.encode(payload));

        assertThat(decoded.eventCode()).isEqualTo("DEPLOYMENT_SUCCESS");
        assertThat(decoded.title()).isEqualTo("Deployment succeeded");
        assertThat(decoded.summary()).isEqualTo("Operation completed");
        assertThat(decoded.fields()).containsExactly(new NotificationField("Project", "homeops", true));
        assertThat(decoded.timestamp()).isEqualTo(Instant.parse("2026-08-19T00:00:00Z"));
    }

    @Test
    void should_rejectPayload_when_serializedBytesExceedBound() {
        NotificationPayloadCodec codec = new NotificationPayloadCodec(mapper, 64);

        assertThatThrownBy(() -> codec.encode(payload("x".repeat(100), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byte limit");
    }

    @Test
    void should_rejectStoredPayload_when_jsonIsMalformedOrOversized() {
        NotificationPayloadCodec codec = new NotificationPayloadCodec(mapper, 128);

        assertThatThrownBy(() -> codec.decode("{not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Stored notification payload is invalid");
        assertThatThrownBy(() -> codec.decode("x".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Stored notification payload is invalid");
    }

    @Test
    void should_rejectPayload_when_fieldOrTotalCharacterBoundsAreExceeded() {
        assertThatThrownBy(() -> new NotificationPayload(
                "DEPLOYMENT_SUCCESS", "Deployment succeeded", "summary",
                List.of(
                        field("1"), field("2"), field("3"), field("4"),
                        field("5"), field("6"), field("7")),
                Instant.parse("2026-08-19T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most six");
        assertThatThrownBy(() -> new NotificationField("Name", "x".repeat(257), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded");
        assertThatThrownBy(() -> new NotificationPayload(
                "DEPLOYMENT_SUCCESS",
                "x".repeat(128),
                "x".repeat(1_000),
                List.of(
                        new NotificationField("x".repeat(64), "x".repeat(256), false),
                        new NotificationField("x".repeat(64), "x".repeat(256), false),
                        new NotificationField("x".repeat(64), "x".repeat(256), false),
                        new NotificationField("x".repeat(64), "x".repeat(256), false),
                        new NotificationField("x".repeat(64), "x".repeat(256), false),
                        new NotificationField("x".repeat(64), "x".repeat(256), false)),
                Instant.parse("2026-08-19T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("character limit");
    }

    private static NotificationPayload payload(String summary, List<NotificationField> fields) {
        return new NotificationPayload(
                "DEPLOYMENT_SUCCESS", "Deployment succeeded", summary,
                fields, Instant.parse("2026-08-19T00:00:00Z"));
    }

    private static NotificationField field(String value) {
        return new NotificationField("Field", value, false);
    }
}
