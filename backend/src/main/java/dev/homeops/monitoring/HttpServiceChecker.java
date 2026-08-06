package dev.homeops.monitoring;

import dev.homeops.monitoring.api.MonitoredServiceResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class HttpServiceChecker {
    private final HttpClient client;
    private final Clock clock;
    private final SafeServiceUrlPolicy serviceUrlPolicy;

    public HttpServiceChecker(SafeServiceUrlPolicy serviceUrlPolicy) {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), Clock.systemUTC(), serviceUrlPolicy);
    }

    HttpServiceChecker(HttpClient client, Clock clock, SafeServiceUrlPolicy serviceUrlPolicy) {
        this.client = client;
        this.clock = clock;
        this.serviceUrlPolicy = serviceUrlPolicy;
    }

    public Result check(MonitoredServiceResponse service) {
        serviceUrlPolicy.validate(service.url());
        Instant started = clock.instant();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(service.url()))
                    .method(service.method(), HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofMillis(service.timeoutMs())).build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return new Result(status == service.expectedStatus(), status,
                    elapsedMillis(started), null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CheckInterruptedException(exception);
        } catch (IOException | IllegalArgumentException exception) {
            return new Result(false, null, elapsedMillis(started),
                    exception.getClass().getSimpleName());
        }
    }

    private long elapsedMillis(Instant started) {
        return Math.max(0, Duration.between(started, clock.instant()).toMillis());
    }

    public record Result(boolean healthy, Integer httpStatus, long responseTimeMs, String errorCode) { }

    static final class CheckInterruptedException extends RuntimeException {
        CheckInterruptedException(InterruptedException cause) {
            super("Service check was interrupted", cause);
        }
    }
}
