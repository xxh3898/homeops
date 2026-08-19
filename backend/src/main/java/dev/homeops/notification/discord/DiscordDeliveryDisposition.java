package dev.homeops.notification.discord;

public enum DiscordDeliveryDisposition {
    SUCCESS,
    RETRYABLE,
    TERMINAL,
    UNKNOWN
}
