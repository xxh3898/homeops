package dev.homeops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.homeops.monitoring.api.MonitoredServiceResponse;
import dev.homeops.monitoring.config.HomeOpsMonitoringProperties;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@ExtendWith(MockitoExtension.class)
class HttpServiceCheckerTest {
    @Mock private HttpClient client;
    @Mock private Clock clock;
    @Mock private HttpResponse<Void> response;

    @AfterEach
    void clearInterruptedFlag() {
        Thread.interrupted();
    }

    @Test
    void should_returnHealthyAndBoundRequest_when_expectedStatusIsReceived() throws Exception {
        Instant started = Instant.parse("2026-08-06T12:00:00Z");
        when(clock.instant()).thenReturn(started, started.plusMillis(25));
        when(response.statusCode()).thenReturn(204);
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenReturn(response);
        HttpServiceChecker checker = new HttpServiceChecker(client, clock, allowedPolicy());

        HttpServiceChecker.Result result = checker.check(service(204));

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(client).send(request.capture(),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(3));
        assertThat(request.getValue().method()).isEqualTo("HEAD");
        assertThat(result).isEqualTo(new HttpServiceChecker.Result(true, 204, 25, null));
    }

    @Test
    void should_returnBoundedFailure_when_transportFails() throws Exception {
        Instant started = Instant.parse("2026-08-06T12:00:00Z");
        when(clock.instant()).thenReturn(started, started.plusSeconds(3));
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenThrow(new IOException("connection failed"));
        HttpServiceChecker checker = new HttpServiceChecker(client, clock, allowedPolicy());

        HttpServiceChecker.Result result = checker.check(service(200));

        assertThat(result).isEqualTo(new HttpServiceChecker.Result(false, null, 3_000, "IOException"));
    }

    @Test
    void should_propagateCancellation_when_checkIsInterrupted() throws Exception {
        when(client.send(any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenThrow(new InterruptedException("shutdown"));
        HttpServiceChecker checker = new HttpServiceChecker(client, clock, allowedPolicy());

        assertThatThrownBy(() -> checker.check(service(200)))
                .isInstanceOf(HttpServiceChecker.CheckInterruptedException.class);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void should_notIssueHttpRequest_when_persistedServiceOriginIsNoLongerAllowed() {
        SafeServiceUrlPolicy emptyPolicy = new SafeServiceUrlPolicy(new HomeOpsMonitoringProperties(
                List.of(), Duration.ofDays(7), Duration.ofDays(30), 4));
        HttpServiceChecker checker = new HttpServiceChecker(client, clock, emptyPolicy);

        assertThatThrownBy(() -> checker.check(service(200)))
                .isInstanceOf(SafeServiceUrlPolicy.UnsafeServiceUrlException.class);

        verifyNoInteractions(client, clock);
    }

    @Test
    void should_createCheckerBean_when_policyIsRegistered() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    HomeOpsMonitoringProperties.class,
                    () -> new HomeOpsMonitoringProperties(
                            List.of("https://homeops.example.ts.net:9443"),
                            Duration.ofDays(7), Duration.ofDays(30), 4));
            context.register(SafeServiceUrlPolicy.class, HttpServiceChecker.class);

            context.refresh();

            assertThat(context.getBean(HttpServiceChecker.class)).isNotNull();
        }
    }

    private static SafeServiceUrlPolicy allowedPolicy() {
        return new SafeServiceUrlPolicy(new HomeOpsMonitoringProperties(
                List.of("https://homeops.example.ts.net:9443"), Duration.ofDays(7), Duration.ofDays(30), 4));
    }

    private static MonitoredServiceResponse service(int expectedStatus) {
        return new MonitoredServiceResponse(UUID.randomUUID(), "HomeOps",
                "https://homeops.example.ts.net:9443/actuator/health/readiness", "HEAD",
                expectedStatus, 3_000, 30, 3, 2, "WARNING", true, false);
    }
}
