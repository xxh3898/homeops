package dev.homeops.notification.discord;

import dev.homeops.notification.config.HomeOpsNotificationProperties;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class JdkDiscordHttpTransport implements DiscordHttpTransport {
    private final HttpClient client;

    @Autowired
    JdkDiscordHttpTransport(HomeOpsNotificationProperties properties) {
        this(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    JdkDiscordHttpTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public DiscordHttpResponse send(
            DiscordWebhookEndpoint endpoint,
            byte[] body,
            Duration requestTimeout,
            int responseMaxBytes) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint.executionUri())
                .header("Content-Type", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        BoundedBodyHandler bodyHandler = new BoundedBodyHandler(responseMaxBytes);
        long startedAtNanos = System.nanoTime();
        CompletableFuture<HttpResponse<BoundedBody>> responseFuture =
                client.sendAsync(request, bodyHandler);
        try {
            long elapsedNanos = System.nanoTime() - startedAtNanos;
            long remainingNanos = requestTimeout.toNanos() - elapsedNanos;
            if (remainingNanos <= 0) {
                throw responseTimeout(bodyHandler, responseFuture);
            }
            HttpResponse<BoundedBody> response = responseFuture.get(
                    remainingNanos, TimeUnit.NANOSECONDS);
            BoundedBody boundedBody = response.body();
            return new DiscordHttpResponse(
                    response.statusCode(), response.headers().map(),
                    boundedBody.bytes(), boundedBody.exceeded());
        } catch (TimeoutException exception) {
            throw responseTimeout(bodyHandler, responseFuture);
        } catch (InterruptedException exception) {
            cancel(bodyHandler, responseFuture);
            throw exception;
        } catch (ExecutionException exception) {
            cancel(bodyHandler, responseFuture);
            Throwable cause = unwrap(exception.getCause());
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Discord HTTP exchange failed");
        }
    }

    HttpClient client() {
        return client;
    }

    private static HttpTimeoutException responseTimeout(
            BoundedBodyHandler bodyHandler,
            CompletableFuture<HttpResponse<BoundedBody>> responseFuture) {
        cancel(bodyHandler, responseFuture);
        return new HttpTimeoutException("Discord response deadline exceeded");
    }

    private static void cancel(
            BoundedBodyHandler bodyHandler,
            CompletableFuture<HttpResponse<BoundedBody>> responseFuture) {
        responseFuture.cancel(true);
        bodyHandler.cancel();
    }

    private static Throwable unwrap(Throwable cause) {
        Throwable current = cause;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static final class BoundedBodyHandler implements HttpResponse.BodyHandler<BoundedBody> {
        private final int maximumBytes;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<BoundedBodySubscriber> subscriber = new AtomicReference<>();

        BoundedBodyHandler(int maximumBytes) {
            if (maximumBytes < 1 || maximumBytes == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Discord response bound is invalid");
            }
            this.maximumBytes = maximumBytes;
        }

        @Override
        public HttpResponse.BodySubscriber<BoundedBody> apply(HttpResponse.ResponseInfo responseInfo) {
            BoundedBodySubscriber created = new BoundedBodySubscriber(maximumBytes);
            if (!subscriber.compareAndSet(null, created)) {
                created.cancel();
                throw new IllegalStateException("Discord response body handler cannot be reused");
            }
            if (cancelled.get()) {
                created.cancel();
            }
            return created;
        }

        void cancel() {
            cancelled.set(true);
            BoundedBodySubscriber current = subscriber.get();
            if (current != null) {
                current.cancel();
            }
        }
    }

    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<BoundedBody> {
        private final int captureLimit;
        private final byte[] captured;
        private final CompletableFuture<BoundedBody> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean completed;
        private int capturedBytes;

        BoundedBodySubscriber(int maximumBytes) {
            if (maximumBytes < 1 || maximumBytes == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Discord response bound is invalid");
            }
            this.captureLimit = maximumBytes + 1;
            this.captured = new byte[captureLimit];
        }

        @Override
        public CompletionStage<BoundedBody> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription candidate) {
            Objects.requireNonNull(candidate, "Discord response subscription is required");
            synchronized (this) {
                if (subscription != null || completed) {
                    candidate.cancel();
                    return;
                }
                subscription = candidate;
            }
            candidate.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            Objects.requireNonNull(buffers, "Discord response buffers are required");
            Flow.Subscription current;
            BoundedBody result = null;
            synchronized (this) {
                if (completed) {
                    return;
                }
                current = subscription;
                for (ByteBuffer buffer : buffers) {
                    ByteBuffer source = Objects.requireNonNull(buffer).duplicate();
                    while (source.hasRemaining() && capturedBytes < captureLimit) {
                        int count = Math.min(
                                source.remaining(), captureLimit - capturedBytes);
                        source.get(captured, capturedBytes, count);
                        capturedBytes += count;
                    }
                    if (capturedBytes == captureLimit) {
                        completed = true;
                        result = new BoundedBody(
                                Arrays.copyOf(captured, capturedBytes), true);
                        break;
                    }
                }
            }
            if (result != null) {
                if (current != null) {
                    current.cancel();
                }
                body.complete(result);
                return;
            }
            if (current != null) {
                current.request(1);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            Objects.requireNonNull(throwable, "Discord response failure is required");
            synchronized (this) {
                if (completed) {
                    return;
                }
                completed = true;
            }
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            BoundedBody result;
            synchronized (this) {
                if (completed) {
                    return;
                }
                completed = true;
                result = new BoundedBody(
                        Arrays.copyOf(captured, capturedBytes), false);
            }
            body.complete(result);
        }

        void cancel() {
            Flow.Subscription current;
            synchronized (this) {
                if (completed) {
                    return;
                }
                completed = true;
                current = subscription;
            }
            if (current != null) {
                current.cancel();
            }
            body.completeExceptionally(new CancellationException(
                    "Discord response body was cancelled"));
        }
    }

    static record BoundedBody(byte[] bytes, boolean exceeded) {
        BoundedBody {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public String toString() {
            return "BoundedBody[bytes=redacted, exceeded=" + exceeded + "]";
        }
    }
}
