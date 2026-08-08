package dev.homeops.monitoring;

import dev.homeops.monitoring.MonitoredServiceStore.OpenIncident;
import dev.homeops.monitoring.api.MonitoredServiceResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceCheckCoordinator {
    private final MonitoredServiceStore store;
    private final Clock clock;

    @Autowired
    public ServiceCheckCoordinator(MonitoredServiceStore store) {
        this(store, Clock.systemUTC());
    }

    ServiceCheckCoordinator(MonitoredServiceStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    public void record(MonitoredServiceResponse service, HttpServiceChecker.Result result) {
        Instant checkedAt = clock.instant();
        int previousFailures = result.healthy() ? 0 : store.consecutiveStatusCount(service.id(), "DOWN");
        int previousSuccesses = result.healthy()
                ? store.consecutiveStatusCount(service.id(), "HEALTHY") : 0;
        Optional<OpenIncident> incident = store.findOpenIncident(service.id());

        store.recordResult(service.id(), checkedAt, result);
        IncidentStateMachine.Transition transition = IncidentStateMachine.evaluate(
                previousFailures, previousSuccesses, service.failureThreshold(),
                service.recoveryThreshold(), incident.isPresent(), result.healthy());

        switch (transition.action()) {
            case OPEN -> store.openIncident(service, checkedAt);
            case RESOLVE -> store.resolveIncident(incident.orElseThrow().id(), checkedAt);
            case NONE -> incident.filter(ignored -> !result.healthy())
                    .ifPresent(open -> store.observeIncident(open.id(), checkedAt));
        }
    }
}
