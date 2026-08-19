package dev.homeops.notification.discord;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record DiscordDeliveryResult(
        DiscordDeliveryDisposition disposition,
        String failureCode,
        Duration retryAfter) {

    public DiscordDeliveryResult {
        Objects.requireNonNull(disposition, "Discord delivery disposition must be configured");
        if (failureCode != null && !failureCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Discord delivery failure code is invalid");
        }
        if (retryAfter != null && (retryAfter.isNegative() || retryAfter.isZero())) {
            throw new IllegalArgumentException("Discord retry delay must be positive");
        }
        if (disposition == DiscordDeliveryDisposition.SUCCESS
                && (failureCode != null || retryAfter != null)) {
            throw new IllegalArgumentException("Successful Discord delivery cannot have failure metadata");
        }
        if (disposition != DiscordDeliveryDisposition.SUCCESS && failureCode == null) {
            throw new IllegalArgumentException("Failed Discord delivery must have a failure code");
        }
        if (disposition != DiscordDeliveryDisposition.RETRYABLE && retryAfter != null) {
            throw new IllegalArgumentException("Only retryable Discord delivery can carry a retry delay");
        }
    }

    public static DiscordDeliveryResult success() {
        return new DiscordDeliveryResult(DiscordDeliveryDisposition.SUCCESS, null, null);
    }

    public static DiscordDeliveryResult retryable(String code, Duration retryAfter) {
        return new DiscordDeliveryResult(DiscordDeliveryDisposition.RETRYABLE, code, retryAfter);
    }

    public static DiscordDeliveryResult terminal(String code) {
        return new DiscordDeliveryResult(DiscordDeliveryDisposition.TERMINAL, code, null);
    }

    public static DiscordDeliveryResult unknown(String code) {
        return new DiscordDeliveryResult(DiscordDeliveryDisposition.UNKNOWN, code, null);
    }

    public Optional<Duration> serverRetryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    @Override
    public String toString() {
        return "DiscordDeliveryResult[disposition=" + disposition + ", failureCode=" + failureCode + "]";
    }
}
