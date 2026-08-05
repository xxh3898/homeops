package dev.homeops.monitoring;

import dev.homeops.monitoring.api.MonitoredServiceResponse;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ServiceCheckScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceCheckScheduler.class);
    private final MonitoredServiceStore services;
    private final HttpServiceChecker checker;
    private final ServiceCheckCoordinator coordinator;
    private final Clock clock;

    @Autowired
    public ServiceCheckScheduler(MonitoredServiceStore services, HttpServiceChecker checker,
            ServiceCheckCoordinator coordinator) {
        this(services, checker, coordinator, Clock.systemUTC());
    }

    ServiceCheckScheduler(MonitoredServiceStore services, HttpServiceChecker checker,
            ServiceCheckCoordinator coordinator, Clock clock) {
        this.services = services;
        this.checker = checker;
        this.coordinator = coordinator;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${homeops.monitoring.scheduler-delay}")
    public void checkEnabledServices() {
        services.findDue(clock.instant()).forEach(this::checkSafely);
    }

    private void checkSafely(MonitoredServiceResponse service) {
        try {
            coordinator.record(service, checker.check(service));
        } catch (RuntimeException exception) {
            LOGGER.warn("Service check could not be recorded for service {}", service.id());
        }
    }
}
