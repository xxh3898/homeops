package dev.homeops.notification;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class NotificationPayload {
    static final int MAXIMUM_FIELDS = 6;
    static final int MAXIMUM_TOTAL_CHARACTERS = 3_000;
    private static final int MAXIMUM_EVENT_CODE_CHARACTERS = 64;
    private static final int MAXIMUM_TITLE_CHARACTERS = 128;
    private static final int MAXIMUM_SUMMARY_CHARACTERS = 1_000;

    private final String eventCode;
    private final String title;
    private final String summary;
    private final List<NotificationField> fields;
    private final Instant timestamp;

    public NotificationPayload(
            String eventCode,
            String title,
            String summary,
            List<NotificationField> fields,
            Instant timestamp) {
        if (eventCode == null || !eventCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Notification event code must use the bounded allowlist");
        }
        requireBounded(title, MAXIMUM_TITLE_CHARACTERS, "Notification title");
        requireBounded(summary, MAXIMUM_SUMMARY_CHARACTERS, "Notification summary");
        Objects.requireNonNull(fields, "Notification fields must be configured");
        if (fields.size() > MAXIMUM_FIELDS || fields.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Notification fields must contain at most six values");
        }
        this.fields = List.copyOf(fields);
        this.timestamp = Objects.requireNonNull(timestamp, "Notification timestamp must be configured");
        this.eventCode = eventCode;
        this.title = title;
        this.summary = summary;

        int totalCharacters = "Event ".length() + eventCode.length()
                + title.length() + summary.length();
        totalCharacters += this.fields.stream()
                .mapToInt(field -> field.name().length() + field.value().length())
                .sum();
        if (totalCharacters > MAXIMUM_TOTAL_CHARACTERS) {
            throw new IllegalArgumentException("Notification payload exceeds the character limit");
        }
    }

    public String eventCode() {
        return eventCode;
    }

    public String title() {
        return title;
    }

    public String summary() {
        return summary;
    }

    public List<NotificationField> fields() {
        return fields;
    }

    public Instant timestamp() {
        return timestamp;
    }

    private static void requireBounded(String value, int maximum, String label) {
        Objects.requireNonNull(value, label + " must be configured");
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(label + " must be non-blank and bounded");
        }
    }

    @Override
    public String toString() {
        return "NotificationPayload[eventCode=" + eventCode + ", content=redacted]";
    }
}
