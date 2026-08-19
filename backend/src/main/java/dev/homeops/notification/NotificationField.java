package dev.homeops.notification;

import java.util.Objects;

public record NotificationField(String name, String value, boolean inline) {
    private static final int MAXIMUM_NAME_CHARACTERS = 64;
    private static final int MAXIMUM_VALUE_CHARACTERS = 256;

    public NotificationField {
        requireBounded(name, MAXIMUM_NAME_CHARACTERS, "Notification field name");
        requireBounded(value, MAXIMUM_VALUE_CHARACTERS, "Notification field value");
    }

    private static void requireBounded(String value, int maximum, String label) {
        Objects.requireNonNull(value, label + " must be configured");
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(label + " must be non-blank and bounded");
        }
    }

    @Override
    public String toString() {
        return "NotificationField[redacted]";
    }
}
