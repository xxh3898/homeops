package dev.homeops.monitoring;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.homeops.monitoring.MonitoredServiceStore.OpenIncident;
import dev.homeops.monitoring.api.MonitoredServiceResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServiceCheckCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final UUID SERVICE_ID = UUID.fromString("10000000-0000-0000-0000-000000000100");

    @Mock private MonitoredServiceStore store;
    private ServiceCheckCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new ServiceCheckCoordinator(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void should_openIncident_when_failureThresholdIsReached() {
        MonitoredServiceResponse service = service(3, 2);
        HttpServiceChecker.Result result = new HttpServiceChecker.Result(false, 503, 20, null);
        when(store.consecutiveStatusCount(SERVICE_ID, "DOWN")).thenReturn(2);
        when(store.findOpenIncident(SERVICE_ID)).thenReturn(Optional.empty());

        coordinator.record(service, result);

        verify(store).recordResult(SERVICE_ID, NOW, result);
        verify(store).openIncident(service, NOW);
    }

    @Test
    void should_resolveIncident_when_recoveryThresholdIsReached() {
        MonitoredServiceResponse service = service(3, 2);
        OpenIncident incident = new OpenIncident(UUID.randomUUID(), "OPEN");
        HttpServiceChecker.Result result = new HttpServiceChecker.Result(true, 200, 15, null);
        when(store.consecutiveStatusCount(SERVICE_ID, "HEALTHY")).thenReturn(1);
        when(store.findOpenIncident(SERVICE_ID)).thenReturn(Optional.of(incident));

        coordinator.record(service, result);

        verify(store).resolveIncident(incident.id(), NOW);
        verify(store, never()).openIncident(service, NOW);
    }

    @Test
    void should_observeExistingIncident_when_failureContinues() {
        MonitoredServiceResponse service = service(3, 2);
        OpenIncident incident = new OpenIncident(UUID.randomUUID(), "ACKNOWLEDGED");
        HttpServiceChecker.Result result = new HttpServiceChecker.Result(false, null, 3_000, "HttpTimeoutException");
        when(store.consecutiveStatusCount(SERVICE_ID, "DOWN")).thenReturn(5);
        when(store.findOpenIncident(SERVICE_ID)).thenReturn(Optional.of(incident));

        coordinator.record(service, result);

        verify(store).observeIncident(incident.id(), NOW);
        verify(store, never()).openIncident(service, NOW);
    }

    private static MonitoredServiceResponse service(int failureThreshold, int recoveryThreshold) {
        return new MonitoredServiceResponse(SERVICE_ID, "HomeOps", "https://homeops.example.ts.net:9443/health",
                "GET", 200, 3_000, 30, failureThreshold, recoveryThreshold,
                "CRITICAL", true, false);
    }
}
