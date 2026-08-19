package dev.homeops.notification.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.homeops.notification.NotificationField;
import dev.homeops.notification.NotificationPayload;
import dev.homeops.notification.NotificationSeverity;
import dev.homeops.notification.config.HomeOpsNotificationProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DiscordNotificationClientTest {
    private static final String TOKEN = "synthetic_token_" + "x".repeat(48);
    private static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/" + TOKEN;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void should_sendOneBoundedEmbedAndConfirmSuccess_when_waitResponseHasMessageId() throws Exception {
        FakeTransport transport = new FakeTransport(response(200, Map.of(), "{\"id\":\"123\"}"));
        DiscordNotificationClient client = client(transport, properties(65_536));

        DiscordDeliveryResult result = client.send(payload(), NotificationSeverity.WARNING);

        assertThat(result).isEqualTo(DiscordDeliveryResult.success());
        assertThat(transport.endpoint.executionUri().getRawQuery()).isEqualTo("wait=true");
        assertThat(transport.timeout).isEqualTo(Duration.ofSeconds(5));
        JsonNode document = mapper.readTree(transport.requestBody);
        assertThat(document.has("content")).isFalse();
        assertThat(document.has("username")).isFalse();
        assertThat(document.has("avatar_url")).isFalse();
        assertThat(document.has("attachments")).isFalse();
        assertThat(document.get("allowed_mentions").get("parse").size()).isZero();
        assertThat(document.get("embeds").size()).isEqualTo(1);
        assertThat(document.get("embeds").get(0).get("fields").size()).isEqualTo(1);
        assertThat(new String(transport.requestBody, StandardCharsets.UTF_8)).doesNotContain(TOKEN);
    }

    @Test
    void should_markDeliveryUnknownWithoutRetry_when_successConfirmationIsMalformed() {
        DiscordNotificationClient client = client(
                new FakeTransport(response(200, Map.of(), "{\"unexpected\":true}")),
                properties(65_536));

        assertThat(client.send(payload(), NotificationSeverity.INFO))
                .isEqualTo(DiscordDeliveryResult.unknown("SUCCESS_NOT_CONFIRMED"));
    }

    @Test
    void should_classifyKnownHttpFailures_withoutPersistingRemoteBody() {
        String sensitiveBody = "synthetic-sensitive-response-body";

        assertThat(deliver(408, sensitiveBody)).isEqualTo(
                DiscordDeliveryResult.retryable("HTTP_408", null));
        assertThat(deliver(500, sensitiveBody)).isEqualTo(
                DiscordDeliveryResult.retryable("HTTP_5XX", null));
        assertThat(deliver(400, sensitiveBody)).isEqualTo(
                DiscordDeliveryResult.terminal("HTTP_4XX"));
        assertThat(deliver(401, sensitiveBody)).isEqualTo(
                DiscordDeliveryResult.terminal("HTTP_4XX"));
        assertThat(deliver(403, sensitiveBody)).isEqualTo(
                DiscordDeliveryResult.terminal("HTTP_4XX"));
        assertThat(deliver(404, sensitiveBody)).isEqualTo(
                DiscordDeliveryResult.terminal("HTTP_4XX"));
        DiscordDeliveryResult redirect = deliver(302, sensitiveBody);
        assertThat(redirect).isEqualTo(DiscordDeliveryResult.terminal("REDIRECT_REJECTED"));
        assertThat(redirect.toString()).doesNotContain(sensitiveBody);
    }

    @Test
    void should_useLargestServerRateLimitDelay_asMinimumRetryDelay() {
        DiscordHttpResponse response = response(429,
                Map.of("Retry-After", List.of("10.0")),
                "{\"retry_after\":12.5}");
        DiscordNotificationClient client = client(new FakeTransport(response), properties(65_536));

        DiscordDeliveryResult result = client.send(payload(), NotificationSeverity.WARNING);

        assertThat(result.disposition()).isEqualTo(DiscordDeliveryDisposition.RETRYABLE);
        assertThat(result.failureCode()).isEqualTo("HTTP_429");
        assertThat(result.serverRetryAfter()).contains(Duration.ofMillis(12_500));
    }

    @Test
    void should_failClosedWithoutRetry_when_serverRateLimitDelayExceedsBound() {
        DiscordNotificationClient client = client(new FakeTransport(response(
                429, Map.of(), "{\"retry_after\":901}")), properties(65_536));

        assertThat(client.send(payload(), NotificationSeverity.WARNING))
                .isEqualTo(DiscordDeliveryResult.terminal("RATE_LIMIT_DELAY_OUT_OF_RANGE"));
    }

    @Test
    void should_boundRemoteResponseBody_withoutReturningRawContent() {
        String oversized = "x".repeat(65);
        DiscordNotificationClient client = client(
                new FakeTransport(response(200, Map.of(), oversized)), properties(64));

        DiscordDeliveryResult result = client.send(payload(), NotificationSeverity.INFO);

        assertThat(result).isEqualTo(DiscordDeliveryResult.unknown("SUCCESS_RESPONSE_TOO_LARGE"));
        assertThat(result.toString()).doesNotContain(oversized);
    }

    @Test
    void should_retryOnlyKnownPreSendConnectionFailures_andTreatOtherTimeoutsAsUnknown() {
        assertThat(deliverFailure(new HttpConnectTimeoutException("synthetic")))
                .isEqualTo(DiscordDeliveryResult.retryable("CONNECT_FAILED", null));
        assertThat(deliverFailure(new ConnectException("synthetic")))
                .isEqualTo(DiscordDeliveryResult.retryable("CONNECT_FAILED", null));
        assertThat(deliverFailure(new HttpTimeoutException("synthetic")))
                .isEqualTo(DiscordDeliveryResult.unknown("REQUEST_TIMEOUT"));
        assertThat(deliverFailure(new IOException("synthetic")))
                .isEqualTo(DiscordDeliveryResult.unknown("TRANSPORT_OUTCOME_UNKNOWN"));
    }

    @Test
    void should_createJdkClientWithNoRedirectsAndBoundedConnectTimeout() {
        JdkDiscordHttpTransport transport = new JdkDiscordHttpTransport(properties(65_536));

        assertThat(transport.client().followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        assertThat(transport.client().connectTimeout()).contains(Duration.ofSeconds(3));
    }

    @Test
    void should_buildFixedPostRequest_when_jdkTransportSends() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<java.io.InputStream>>any()))
                .thenReturn(response);
        JdkDiscordHttpTransport transport = new JdkDiscordHttpTransport(http);
        DiscordWebhookEndpoint endpoint = DiscordWebhookEndpoint.parse(WEBHOOK);

        try (DiscordHttpResponse ignored = transport.send(
                endpoint, "{}".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5))) {
            // Response body ownership is transferred to the bounded caller.
        }

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(http).send(request.capture(),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<java.io.InputStream>>any());
        assertThat(request.getValue().method()).isEqualTo("POST");
        assertThat(request.getValue().uri().getRawQuery()).isEqualTo("wait=true");
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(5));
        assertThat(request.getValue().headers().firstValue("Content-Type"))
                .contains("application/json");
        assertThat(request.getValue().uri().toString()).contains(TOKEN);
    }

    private DiscordDeliveryResult deliver(int status, String body) {
        return client(new FakeTransport(response(status, Map.of(), body)), properties(65_536))
                .send(payload(), NotificationSeverity.WARNING);
    }

    private DiscordDeliveryResult deliverFailure(IOException failure) {
        return client(new FakeTransport(failure), properties(65_536))
                .send(payload(), NotificationSeverity.WARNING);
    }

    private DiscordNotificationClient client(
            DiscordHttpTransport transport,
            HomeOpsNotificationProperties properties) {
        return new DiscordNotificationClient(
                transport,
                new DiscordMessageRenderer(mapper, properties.payloadMaxBytes()),
                mapper,
                properties);
    }

    private static NotificationPayload payload() {
        return new NotificationPayload(
                "INCIDENT_OPEN",
                "Service unavailable",
                "A monitored service crossed its failure threshold.",
                List.of(new NotificationField("Severity", "WARNING", true)),
                Instant.parse("2026-08-19T01:02:03Z"));
    }

    private static HomeOpsNotificationProperties properties(int responseMaxBytes) {
        return new HomeOpsNotificationProperties(
                true, WEBHOOK,
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofSeconds(1), 10, 6,
                Duration.ofSeconds(5), Duration.ofMinutes(15),
                responseMaxBytes, 8_192,
                Duration.ofDays(30), Duration.ofDays(90));
    }

    private static DiscordHttpResponse response(
            int status,
            Map<String, List<String>> headers,
            String body) {
        return new DiscordHttpResponse(status, headers,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class FakeTransport implements DiscordHttpTransport {
        private final DiscordHttpResponse response;
        private final IOException failure;
        private DiscordWebhookEndpoint endpoint;
        private byte[] requestBody;
        private Duration timeout;

        private FakeTransport(DiscordHttpResponse response) {
            this.response = response;
            this.failure = null;
        }

        private FakeTransport(IOException failure) {
            this.response = null;
            this.failure = failure;
        }

        @Override
        public DiscordHttpResponse send(
                DiscordWebhookEndpoint endpoint,
                byte[] body,
                Duration requestTimeout) throws IOException {
            this.endpoint = endpoint;
            this.requestBody = body.clone();
            this.timeout = requestTimeout;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
