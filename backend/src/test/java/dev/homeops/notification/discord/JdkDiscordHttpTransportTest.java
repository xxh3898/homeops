package dev.homeops.notification.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.homeops.notification.NotificationField;
import dev.homeops.notification.NotificationPayload;
import dev.homeops.notification.NotificationSeverity;
import dev.homeops.notification.config.HomeOpsNotificationProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class JdkDiscordHttpTransportTest {
    private static final String TOKEN = "synthetic_token_" + "x".repeat(48);
    private static final String WEBHOOK =
            "https://discord.com/api/webhooks/123456789012345678/" + TOKEN;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void should_buildFixedPostAndConfirmSuccess_when_bodyCompletesAtExactByteBound() {
        byte[] confirmedBody = exactLengthJson(64);
        ControlledExchange exchange = new ControlledExchange(
                200, Map.of(), subscriber -> publishAndComplete(subscriber, confirmedBody));
        HttpClient http = stubClient(exchange);
        HomeOpsNotificationProperties properties = properties(Duration.ofSeconds(5), 64);
        DiscordNotificationClient client = client(new JdkDiscordHttpTransport(http), properties);

        DiscordDeliveryResult result = client.send(payload(), NotificationSeverity.WARNING);

        assertThat(result).isEqualTo(DiscordDeliveryResult.success());
        assertThat(exchange.subscription.cancelled).isFalse();
        assertThat(exchange.completedBody.bytes()).hasSize(64);
        assertThat(exchange.completedBody.exceeded()).isFalse();
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(http).sendAsync(request.capture(),
                org.mockito.ArgumentMatchers
                        .<HttpResponse.BodyHandler<JdkDiscordHttpTransport.BoundedBody>>any());
        assertThat(request.getValue().method()).isEqualTo("POST");
        assertThat(request.getValue().uri().getRawQuery()).isEqualTo("wait=true");
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(5));
        assertThat(request.getValue().headers().firstValue("Content-Type"))
                .contains("application/json");
    }

    @Test
    void should_returnDeliveryUnknownAndReleaseResources_when_headersArriveButBodyStalls() {
        ControlledExchange exchange = new ControlledExchange(200, Map.of(), subscriber -> {
            // Headers and subscription are available, but no body completion signal follows.
        });
        HomeOpsNotificationProperties properties = properties(Duration.ofMillis(100), 64);
        DiscordNotificationClient client = client(
                new JdkDiscordHttpTransport(stubClient(exchange)), properties);

        DiscordDeliveryResult result = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> client.send(payload(), NotificationSeverity.WARNING));

        assertThat(result).isEqualTo(DiscordDeliveryResult.unknown("REQUEST_TIMEOUT"));
        assertThat(exchange.subscription.cancelled).isTrue();
        assertThat(exchange.responseFuture.isCancelled()).isTrue();
        assertThat(exchange.invocations).isEqualTo(1);
    }

    @Test
    void should_timeoutAndReleaseRealJdkExchange_when_serverStallsAfterResponseHeaders()
            throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        CountDownLatch handlerFinished = new CountDownLatch(1);
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        try {
            server.setExecutor(serverExecutor);
            server.createContext("/discord-test", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, 2);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                    headersSent.countDown();
                    if (releaseBody.await(2, TimeUnit.SECONDS)) {
                        exchange.getResponseBody().write('}');
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (java.io.IOException exception) {
                    // Client cancellation can close the local response stream.
                } finally {
                    handlerFinished.countDown();
                }
            });
            server.start();
            DiscordWebhookEndpoint endpoint = mock(DiscordWebhookEndpoint.class);
            when(endpoint.executionUri()).thenReturn(URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/discord-test"));
            JdkDiscordHttpTransport transport = new JdkDiscordHttpTransport(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build());

            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThrows(HttpTimeoutException.class, () -> transport.send(
                            endpoint,
                            "{}".getBytes(StandardCharsets.UTF_8),
                            Duration.ofMillis(150),
                            64)));
            assertThat(headersSent.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseBody.countDown();
            assertThat(handlerFinished.await(1, TimeUnit.SECONDS)).isTrue();
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void should_notExtendOverallDeadline_when_bodyContinuesInSlowChunks() {
        AtomicInteger chunksPublished = new AtomicInteger();
        try (ScheduledExecutorService publisher = Executors.newSingleThreadScheduledExecutor()) {
            ControlledExchange exchange = new ControlledExchange(200, Map.of(), subscriber -> {
                subscriber.onNext(List.of(ByteBuffer.wrap("{".getBytes(StandardCharsets.UTF_8))));
                chunksPublished.incrementAndGet();
                for (int index = 1; index <= 5; index++) {
                    publisher.schedule(() -> {
                        if (!exchangeCancelled(subscriber)) {
                            subscriber.onNext(List.of(ByteBuffer.wrap(
                                    " ".getBytes(StandardCharsets.UTF_8))));
                            chunksPublished.incrementAndGet();
                        }
                    }, index * 50L, TimeUnit.MILLISECONDS);
                }
            });
            HomeOpsNotificationProperties properties = properties(Duration.ofMillis(500), 64);
            DiscordNotificationClient client = client(
                    new JdkDiscordHttpTransport(stubClient(exchange)), properties);

            DiscordDeliveryResult result = assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () -> client.send(payload(), NotificationSeverity.WARNING));

            assertThat(result).isEqualTo(DiscordDeliveryResult.unknown("REQUEST_TIMEOUT"));
            assertThat(chunksPublished).hasValueGreaterThanOrEqualTo(2);
            assertThat(exchange.subscription.cancelled).isTrue();
            assertThat(exchange.responseFuture.isCancelled()).isTrue();
            assertThat(exchange.invocations).isEqualTo(1);
        }
    }

    @Test
    void should_cancelAfterCapturingOnlyBoundPlusOne_when_bodyIsOversized() {
        JdkDiscordHttpTransport.BoundedBodySubscriber subscriber =
                new JdkDiscordHttpTransport.BoundedBodySubscriber(64);
        RecordingSubscription subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);

        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[4_096])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[4_096])));

        JdkDiscordHttpTransport.BoundedBody body =
                subscriber.getBody().toCompletableFuture().join();
        assertThat(body.exceeded()).isTrue();
        assertThat(body.bytes()).hasSize(65);
        assertThat(subscription.cancelled).isTrue();
    }

    @Test
    void should_returnBoundedOversizedClassification_when_transportReceivesBoundPlusOne() {
        byte[] oversizedBody = new byte[65];
        ControlledExchange exchange = new ControlledExchange(
                200, Map.of(), subscriber -> publishAndComplete(subscriber, oversizedBody));
        HomeOpsNotificationProperties properties = properties(Duration.ofSeconds(5), 64);
        DiscordNotificationClient client = client(
                new JdkDiscordHttpTransport(stubClient(exchange)), properties);

        DiscordDeliveryResult result = client.send(payload(), NotificationSeverity.INFO);

        assertThat(result)
                .isEqualTo(DiscordDeliveryResult.unknown("SUCCESS_RESPONSE_TOO_LARGE"));
        assertThat(exchange.completedBody.exceeded()).isTrue();
        assertThat(exchange.completedBody.bytes()).hasSize(65);
        assertThat(exchange.subscription.cancelled).isTrue();
        assertThat(exchange.invocations).isEqualTo(1);
    }

    @Test
    void should_completeNormally_withoutCancelling_when_bodyIsExactlyAtBound() {
        JdkDiscordHttpTransport.BoundedBodySubscriber subscriber =
                new JdkDiscordHttpTransport.BoundedBodySubscriber(64);
        RecordingSubscription subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);

        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[64])));
        assertThat(subscriber.getBody().toCompletableFuture()).isNotDone();
        subscriber.onComplete();

        JdkDiscordHttpTransport.BoundedBody body =
                subscriber.getBody().toCompletableFuture().join();
        assertThat(body.exceeded()).isFalse();
        assertThat(body.bytes()).hasSize(64);
        assertThat(subscription.cancelled).isFalse();
    }

    @Test
    void should_releaseSubscription_when_bodySubscriberIsCancelled() {
        JdkDiscordHttpTransport.BoundedBodySubscriber subscriber =
                new JdkDiscordHttpTransport.BoundedBodySubscriber(64);
        RecordingSubscription subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);

        subscriber.cancel();

        assertThat(subscription.cancelled).isTrue();
        assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
    }

    @Test
    void should_preserveRetryableClassification_when_asyncConnectFailsBeforeRequest() {
        HttpClient http = failedClient(new HttpConnectTimeoutException("synthetic"));
        HomeOpsNotificationProperties properties = properties(Duration.ofSeconds(5), 64);
        DiscordNotificationClient client = client(new JdkDiscordHttpTransport(http), properties);

        DiscordDeliveryResult result = client.send(payload(), NotificationSeverity.WARNING);

        assertThat(result).isEqualTo(DiscordDeliveryResult.retryable("CONNECT_FAILED", null));
    }

    @Test
    void should_preserveUnknownClassification_when_asyncRequestTimeoutIsAmbiguous() {
        HttpClient http = failedClient(new HttpTimeoutException("synthetic"));
        HomeOpsNotificationProperties properties = properties(Duration.ofSeconds(5), 64);
        DiscordNotificationClient client = client(new JdkDiscordHttpTransport(http), properties);

        DiscordDeliveryResult result = client.send(payload(), NotificationSeverity.WARNING);

        assertThat(result).isEqualTo(DiscordDeliveryResult.unknown("REQUEST_TIMEOUT"));
    }

    private HttpClient stubClient(ControlledExchange exchange) {
        HttpClient http = mock(HttpClient.class);
        when(http.sendAsync(any(HttpRequest.class),
                org.mockito.ArgumentMatchers
                        .<HttpResponse.BodyHandler<JdkDiscordHttpTransport.BoundedBody>>any()))
                .thenAnswer(invocation -> {
                    exchange.invocations++;
                    return exchange.start(invocation.getArgument(1));
                });
        return http;
    }

    private HttpClient failedClient(Exception failure) {
        HttpClient http = mock(HttpClient.class);
        when(http.sendAsync(any(HttpRequest.class),
                org.mockito.ArgumentMatchers
                        .<HttpResponse.BodyHandler<JdkDiscordHttpTransport.BoundedBody>>any()))
                .thenReturn(CompletableFuture.failedFuture(failure));
        return http;
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

    private static HomeOpsNotificationProperties properties(
            Duration requestTimeout,
            int responseMaxBytes) {
        return new HomeOpsNotificationProperties(
                true, WEBHOOK,
                requestTimeout.compareTo(Duration.ofMillis(50)) >= 0
                        ? Duration.ofMillis(50) : requestTimeout,
                requestTimeout,
                requestTimeout.plusSeconds(1),
                Duration.ofSeconds(1), 10, 6,
                Duration.ofSeconds(5), Duration.ofMinutes(15),
                responseMaxBytes, 8_192,
                Duration.ofDays(30), Duration.ofDays(90));
    }

    private static NotificationPayload payload() {
        return new NotificationPayload(
                "INCIDENT_OPEN",
                "Service unavailable",
                "A monitored service crossed its failure threshold.",
                List.of(new NotificationField("Severity", "WARNING", true)),
                Instant.parse("2026-08-19T01:02:03Z"));
    }

    private static byte[] exactLengthJson(int length) {
        byte[] prefix = "{\"id\":\"123\"}".getBytes(StandardCharsets.UTF_8);
        if (prefix.length > length) {
            throw new IllegalArgumentException("Synthetic JSON exceeds requested length");
        }
        byte[] result = new byte[length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        java.util.Arrays.fill(result, prefix.length, result.length, (byte) ' ');
        return result;
    }

    private static void publishAndComplete(
            HttpResponse.BodySubscriber<JdkDiscordHttpTransport.BoundedBody> subscriber,
            byte[] bytes) {
        subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
        subscriber.onComplete();
    }

    private static boolean exchangeCancelled(
            HttpResponse.BodySubscriber<JdkDiscordHttpTransport.BoundedBody> subscriber) {
        if (subscriber instanceof JdkDiscordHttpTransport.BoundedBodySubscriber bounded) {
            return bounded.getBody().toCompletableFuture().isDone();
        }
        return false;
    }

    private static HttpResponse.ResponseInfo responseInfo(
            int status,
            Map<String, List<String>> headers) {
        HttpResponse.ResponseInfo info = mock(HttpResponse.ResponseInfo.class);
        when(info.statusCode()).thenReturn(status);
        when(info.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        when(info.version()).thenReturn(HttpClient.Version.HTTP_1_1);
        return info;
    }

    private static final class ControlledExchange {
        private final int status;
        private final Map<String, List<String>> headers;
        private final Consumer<HttpResponse.BodySubscriber<JdkDiscordHttpTransport.BoundedBody>> publisher;
        private final RecordingSubscription subscription = new RecordingSubscription();
        private CompletableFuture<HttpResponse<JdkDiscordHttpTransport.BoundedBody>> responseFuture;
        private JdkDiscordHttpTransport.BoundedBody completedBody;
        private int invocations;

        private ControlledExchange(
                int status,
                Map<String, List<String>> headers,
                Consumer<HttpResponse.BodySubscriber<JdkDiscordHttpTransport.BoundedBody>> publisher) {
            this.status = status;
            this.headers = headers;
            this.publisher = publisher;
        }

        private CompletableFuture<HttpResponse<JdkDiscordHttpTransport.BoundedBody>> start(
                HttpResponse.BodyHandler<JdkDiscordHttpTransport.BoundedBody> handler) {
            HttpResponse.BodySubscriber<JdkDiscordHttpTransport.BoundedBody> subscriber =
                    handler.apply(responseInfo(status, headers));
            responseFuture = new CompletableFuture<>();
            subscriber.getBody().whenComplete((body, failure) -> {
                if (failure != null) {
                    responseFuture.completeExceptionally(failure);
                    return;
                }
                completedBody = body;
                @SuppressWarnings("unchecked")
                HttpResponse<JdkDiscordHttpTransport.BoundedBody> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(status);
                when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
                when(response.body()).thenReturn(body);
                responseFuture.complete(response);
            });
            subscriber.onSubscribe(subscription);
            publisher.accept(subscriber);
            return responseFuture;
        }
    }

    private static final class RecordingSubscription implements Flow.Subscription {
        private boolean cancelled;

        @Override
        public void request(long count) {
            // The controlled publisher emits only when directed by the test.
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
