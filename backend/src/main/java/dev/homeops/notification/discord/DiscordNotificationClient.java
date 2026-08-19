package dev.homeops.notification.discord;

import dev.homeops.notification.NotificationPayload;
import dev.homeops.notification.NotificationSeverity;
import dev.homeops.notification.config.HomeOpsNotificationProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class DiscordNotificationClient {
    private final DiscordHttpTransport transport;
    private final DiscordMessageRenderer renderer;
    private final ObjectMapper mapper;
    private final HomeOpsNotificationProperties properties;

    DiscordNotificationClient(
            DiscordHttpTransport transport,
            DiscordMessageRenderer renderer,
            ObjectMapper mapper,
            HomeOpsNotificationProperties properties) {
        this.transport = transport;
        this.renderer = renderer;
        this.mapper = mapper;
        this.properties = properties;
    }

    public DiscordDeliveryResult send(NotificationPayload payload, NotificationSeverity severity) {
        Optional<DiscordWebhookEndpoint> endpoint = properties.discordWebhookEndpoint();
        if (endpoint.isEmpty()) {
            return DiscordDeliveryResult.terminal("WEBHOOK_NOT_CONFIGURED");
        }
        byte[] requestBody;
        try {
            requestBody = renderer.render(payload, severity);
        } catch (IllegalArgumentException exception) {
            return DiscordDeliveryResult.terminal("PAYLOAD_INVALID");
        }

        try (DiscordHttpResponse response = transport.send(
                endpoint.orElseThrow(), requestBody, properties.requestTimeout())) {
            BoundedBody body = readBounded(response);
            return classify(response.statusCode(), response.headers(), body);
        } catch (HttpConnectTimeoutException | ConnectException | UnknownHostException exception) {
            return DiscordDeliveryResult.retryable("CONNECT_FAILED", null);
        } catch (HttpTimeoutException exception) {
            return DiscordDeliveryResult.unknown("REQUEST_TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DiscordDeliveryResult.unknown("REQUEST_INTERRUPTED");
        } catch (IOException exception) {
            return DiscordDeliveryResult.unknown("TRANSPORT_OUTCOME_UNKNOWN");
        }
    }

    private DiscordDeliveryResult classify(
            int status,
            Map<String, List<String>> headers,
            BoundedBody body) {
        if (status >= 200 && status < 300) {
            if (body.exceeded()) {
                return DiscordDeliveryResult.unknown("SUCCESS_RESPONSE_TOO_LARGE");
            }
            return hasConfirmedMessageId(body.bytes())
                    ? DiscordDeliveryResult.success()
                    : DiscordDeliveryResult.unknown("SUCCESS_NOT_CONFIRMED");
        }
        if (status >= 300 && status < 400) {
            return DiscordDeliveryResult.terminal("REDIRECT_REJECTED");
        }
        if (status == 408) {
            return DiscordDeliveryResult.retryable("HTTP_408", null);
        }
        if (status == 429) {
            if (body.exceeded()) {
                return DiscordDeliveryResult.terminal("RATE_LIMIT_RESPONSE_TOO_LARGE");
            }
            Optional<Duration> retryAfter = retryAfter(headers, body.bytes());
            if (retryAfter.filter(delay -> delay.compareTo(properties.maxBackoff()) > 0).isPresent()) {
                return DiscordDeliveryResult.terminal("RATE_LIMIT_DELAY_OUT_OF_RANGE");
            }
            return DiscordDeliveryResult.retryable("HTTP_429", retryAfter.orElse(null));
        }
        if (status >= 500 && status < 600) {
            return DiscordDeliveryResult.retryable("HTTP_5XX", null);
        }
        if (status >= 400 && status < 500) {
            return DiscordDeliveryResult.terminal("HTTP_4XX");
        }
        return DiscordDeliveryResult.unknown("UNEXPECTED_HTTP_STATUS");
    }

    private boolean hasConfirmedMessageId(byte[] body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode id = root == null ? null : root.get("id");
            return id != null && id.isString() && !id.stringValue().isBlank();
        } catch (Exception exception) {
            return false;
        }
    }

    private Optional<Duration> retryAfter(Map<String, List<String>> headers, byte[] body) {
        Optional<Duration> header = firstHeader(headers, "Retry-After").flatMap(this::seconds);
        Optional<Duration> document = retryAfterFromDocument(body);
        if (header.isPresent() && document.isPresent()) {
            return Optional.of(header.orElseThrow().compareTo(document.orElseThrow()) >= 0
                    ? header.orElseThrow() : document.orElseThrow());
        }
        return header.or(() -> document);
    }

    private Optional<Duration> retryAfterFromDocument(byte[] body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode value = root == null ? null : root.get("retry_after");
            if (value == null || !value.isNumber()) {
                return Optional.empty();
            }
            return seconds(value.decimalValue().toPlainString());
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private Optional<Duration> seconds(String raw) {
        try {
            java.math.BigDecimal seconds = new java.math.BigDecimal(raw.trim());
            if (seconds.signum() <= 0) {
                return Optional.empty();
            }
            long nanos = seconds.movePointRight(9).longValueExact();
            return Optional.of(Duration.ofNanos(nanos));
        } catch (ArithmeticException | NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static Optional<String> firstHeader(Map<String, List<String>> headers, String expected) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(expected))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst();
    }

    private BoundedBody readBounded(DiscordHttpResponse response) throws IOException {
        int maximum = properties.responseMaxBytes();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8_192))) {
            byte[] buffer = new byte[4_096];
            int total = 0;
            while (true) {
                int read = response.body().read(buffer, 0, Math.min(buffer.length, maximum + 1 - total));
                if (read == -1) {
                    return new BoundedBody(output.toByteArray(), false);
                }
                output.write(buffer, 0, read);
                total += read;
                if (total > maximum) {
                    return new BoundedBody(output.toByteArray(), true);
                }
            }
        }
    }

    private record BoundedBody(byte[] bytes, boolean exceeded) {
        @Override
        public String toString() {
            return "BoundedBody[bytes=redacted, exceeded=" + exceeded + "]";
        }
    }
}
