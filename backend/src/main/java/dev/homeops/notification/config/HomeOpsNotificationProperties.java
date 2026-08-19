package dev.homeops.notification.config;

import dev.homeops.notification.discord.DiscordWebhookEndpoint;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("homeops.notifications")
public final class HomeOpsNotificationProperties {
    private static final Duration MAXIMUM_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MAXIMUM_REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MAXIMUM_LEASE_DURATION = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_POLL_DELAY = Duration.ofMinutes(1);
    private static final Duration MAXIMUM_BACKOFF = Duration.ofMinutes(15);
    private static final Duration MAXIMUM_RETENTION = Duration.ofDays(365);
    private static final int MAXIMUM_BATCH_SIZE = 10;
    private static final int MAXIMUM_ATTEMPTS = 6;
    private static final int MAXIMUM_RESPONSE_BYTES = 64 * 1024;
    private static final int MAXIMUM_PAYLOAD_BYTES = 8 * 1024;

    private final boolean enabled;
    private final String discordWebhookUrl;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration leaseDuration;
    private final Duration pollDelay;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int responseMaxBytes;
    private final int payloadMaxBytes;
    private final Duration sentRetention;
    private final Duration failedRetention;

    public HomeOpsNotificationProperties(
            boolean enabled,
            String discordWebhookUrl,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration leaseDuration,
            Duration pollDelay,
            int batchSize,
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff,
            int responseMaxBytes,
            int payloadMaxBytes,
            Duration sentRetention,
            Duration failedRetention) {
        this.enabled = enabled;
        this.discordWebhookUrl = discordWebhookUrl;
        this.connectTimeout = positiveBounded(connectTimeout, MAXIMUM_CONNECT_TIMEOUT,
                "Notification connect timeout");
        this.requestTimeout = positiveBounded(requestTimeout, MAXIMUM_REQUEST_TIMEOUT,
                "Notification request timeout");
        this.leaseDuration = positiveBounded(leaseDuration, MAXIMUM_LEASE_DURATION,
                "Notification lease duration");
        this.pollDelay = positiveBounded(pollDelay, MAXIMUM_POLL_DELAY,
                "Notification poll delay");
        if (this.connectTimeout.compareTo(this.requestTimeout) > 0) {
            throw new IllegalArgumentException("Notification connect timeout must not exceed request timeout");
        }
        if (this.leaseDuration.compareTo(this.requestTimeout) <= 0) {
            throw new IllegalArgumentException("Notification lease duration must exceed request timeout");
        }
        if (batchSize < 1 || batchSize > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("Notification batch size must be between 1 and 10");
        }
        if (maxAttempts < 1 || maxAttempts > MAXIMUM_ATTEMPTS) {
            throw new IllegalArgumentException("Notification maximum attempts must be between 1 and 6");
        }
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = positiveBounded(initialBackoff, MAXIMUM_BACKOFF,
                "Notification initial backoff");
        this.maxBackoff = positiveBounded(maxBackoff, MAXIMUM_BACKOFF,
                "Notification maximum backoff");
        if (this.initialBackoff.compareTo(this.maxBackoff) > 0) {
            throw new IllegalArgumentException("Notification initial backoff must not exceed maximum backoff");
        }
        if (responseMaxBytes < 1 || responseMaxBytes > MAXIMUM_RESPONSE_BYTES) {
            throw new IllegalArgumentException("Notification response bound must be between 1 and 65536 bytes");
        }
        if (payloadMaxBytes < 1 || payloadMaxBytes > MAXIMUM_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Notification payload bound must be between 1 and 8192 bytes");
        }
        this.responseMaxBytes = responseMaxBytes;
        this.payloadMaxBytes = payloadMaxBytes;
        this.sentRetention = positiveBounded(sentRetention, MAXIMUM_RETENTION,
                "Notification sent retention");
        this.failedRetention = positiveBounded(failedRetention, MAXIMUM_RETENTION,
                "Notification failed retention");
        if (this.failedRetention.compareTo(this.sentRetention) < 0) {
            throw new IllegalArgumentException("Notification failed retention must not be shorter than sent retention");
        }
    }

    private static Duration positiveBounded(Duration value, Duration maximum, String label) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(label + " must be positive and within its supported bound");
        }
        return value;
    }

    public boolean enabled() {
        return enabled;
    }

    public Optional<DiscordWebhookEndpoint> discordWebhookEndpoint() {
        if (discordWebhookUrl == null || discordWebhookUrl.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(DiscordWebhookEndpoint.parse(discordWebhookUrl));
    }

    void validateWebhookConfiguration() {
        if (discordWebhookUrl == null || discordWebhookUrl.isBlank()) {
            if (enabled) {
                throw new IllegalStateException(
                        "Discord webhook must be configured when notifications are enabled");
            }
            return;
        }
        try {
            DiscordWebhookEndpoint.parse(discordWebhookUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Discord webhook configuration is invalid");
        }
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Duration leaseDuration() {
        return leaseDuration;
    }

    public Duration pollDelay() {
        return pollDelay;
    }

    public int batchSize() {
        return batchSize;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration initialBackoff() {
        return initialBackoff;
    }

    public Duration maxBackoff() {
        return maxBackoff;
    }

    public int responseMaxBytes() {
        return responseMaxBytes;
    }

    public int payloadMaxBytes() {
        return payloadMaxBytes;
    }

    public Duration sentRetention() {
        return sentRetention;
    }

    public Duration failedRetention() {
        return failedRetention;
    }

    @Override
    public String toString() {
        return "HomeOpsNotificationProperties[enabled=" + enabled + ", webhook=redacted]";
    }
}
