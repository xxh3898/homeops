package dev.homeops.monitoring;

import dev.homeops.monitoring.api.MonitoredServiceResponse;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ServiceCheckScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceCheckScheduler.class);
    private final MonitoredServiceStore services;
    private final HttpServiceChecker checker;
    private final ServiceCheckCoordinator coordinator;
    private final Clock clock;
    private final Executor executor;

    @Autowired
    public ServiceCheckScheduler(MonitoredServiceStore services, HttpServiceChecker checker,
            ServiceCheckCoordinator coordinator, @Qualifier("serviceCheckExecutor") Executor executor) {
        this(services, checker, coordinator, executor, Clock.systemUTC());
    }

    ServiceCheckScheduler(MonitoredServiceStore services, HttpServiceChecker checker,
            ServiceCheckCoordinator coordinator, Executor executor, Clock clock) {
        this.services = services;
        this.checker = checker;
        this.coordinator = coordinator;
        this.executor = executor;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${homeops.monitoring.scheduler-delay}")
    public void checkEnabledServices() {
        services.findDue(clock.instant()).forEach(service -> {
            try {
                executor.execute(() -> checkSafely(service));
            } catch (RejectedExecutionException exception) {
                LOGGER.warn("Service check was deferred because the bounded executor is full for service {}", service.id());
            }
        });
    }

    private void checkSafely(MonitoredServiceResponse service) {
        try {
            coordinator.record(service, checker.check(service));
        } catch (RuntimeException exception) {
            LOGGER.warn("Service check could not be recorded for service {}", service.id());
        }
    }
}
