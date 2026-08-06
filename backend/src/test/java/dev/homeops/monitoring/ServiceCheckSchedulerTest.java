package dev.homeops.monitoring;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.homeops.monitoring.api.MonitoredServiceResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServiceCheckSchedulerTest {
    @Mock private MonitoredServiceStore store;
    @Mock private HttpServiceChecker checker;
    @Mock private ServiceCheckCoordinator coordinator;

    @Test
    void should_continueWithRemainingServices_when_oneRecordFails() {
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        MonitoredServiceResponse first = service("First");
        MonitoredServiceResponse second = service("Second");
        HttpServiceChecker.Result firstResult = new HttpServiceChecker.Result(false, 503, 10, null);
        HttpServiceChecker.Result secondResult = new HttpServiceChecker.Result(true, 200, 10, null);
        when(store.findDue(now)).thenReturn(List.of(first, second));
        when(checker.check(first)).thenReturn(firstResult);
        when(checker.check(second)).thenReturn(secondResult);
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable"))
                .when(coordinator).record(first, firstResult);
        ServiceCheckScheduler scheduler = new ServiceCheckScheduler(
                store, checker, coordinator, Runnable::run, Clock.fixed(now, ZoneOffset.UTC));

        scheduler.checkEnabledServices();

        verify(coordinator).record(second, secondResult);
    }

    @Test
    void should_submitEveryDueService_when_schedulerRuns() {
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        MonitoredServiceResponse first = service("First");
        MonitoredServiceResponse second = service("Second");
        List<Runnable> submitted = new ArrayList<>();
        Executor deferredExecutor = submitted::add;
        when(store.findDue(now)).thenReturn(List.of(first, second));
        ServiceCheckScheduler scheduler = new ServiceCheckScheduler(
                store, checker, coordinator, deferredExecutor, Clock.fixed(now, ZoneOffset.UTC));

        scheduler.checkEnabledServices();

        org.assertj.core.api.Assertions.assertThat(submitted).hasSize(2);
        org.mockito.Mockito.verifyNoInteractions(checker, coordinator);
    }

    @Test
    void should_leaveServiceDueForNextTick_when_executorRejectsCheck() {
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        MonitoredServiceResponse service = service("First");
        Executor rejectingExecutor = ignored -> { throw new RejectedExecutionException("bounded"); };
        when(store.findDue(now)).thenReturn(List.of(service));
        ServiceCheckScheduler scheduler = new ServiceCheckScheduler(
                store, checker, coordinator, rejectingExecutor, Clock.fixed(now, ZoneOffset.UTC));

        scheduler.checkEnabledServices();

        org.mockito.Mockito.verifyNoInteractions(checker, coordinator);
    }

    private static MonitoredServiceResponse service(String name) {
        return new MonitoredServiceResponse(UUID.randomUUID(), name,
                "https://homeops.example.ts.net:9443/health", "GET", 200,
                3_000, 30, 3, 2, "WARNING", true, false);
    }
}
