package dev.homeops.monitoring;

import dev.homeops.monitoring.MonitoredServiceStore.OpenIncident;
import dev.homeops.monitoring.api.MonitoredServiceResponse;
import dev.homeops.notification.IncidentNotificationProducer;
import dev.homeops.recovery.AutomaticRecoveryDecisionService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceCheckCoordinator {
    private final MonitoredServiceStore store;
    private final IncidentNotificationProducer notifications;
    private final AutomaticRecoveryDecisionService recoveries;
    private final Clock clock;

    @Autowired
    public ServiceCheckCoordinator(
            MonitoredServiceStore store,
            IncidentNotificationProducer notifications,
            AutomaticRecoveryDecisionService recoveries) {
        this(store, notifications, recoveries, Clock.systemUTC());
    }

    public ServiceCheckCoordinator(
            MonitoredServiceStore store,
            IncidentNotificationProducer notifications,
            AutomaticRecoveryDecisionService recoveries,
            Clock clock) {
        this.store = store;
        this.notifications = notifications;
        this.recoveries = recoveries;
        this.clock = clock;
    }

    @Transactional
    public void record(MonitoredServiceResponse service, HttpServiceChecker.Result result) {
        Instant checkedAt = clock.instant();
        boolean notificationEnabled = store.findNotificationAuthorityForUpdate(service.id())
                .orElse(false);
        int previousFailures = result.healthy() ? 0 : store.consecutiveStatusCount(service.id(), "DOWN");
        int previousSuccesses = result.healthy()
                ? store.consecutiveStatusCount(service.id(), "HEALTHY") : 0;
        Optional<OpenIncident> incident = store.findOpenIncident(service.id());

        store.recordResult(service.id(), checkedAt, result);
        IncidentStateMachine.Transition transition = IncidentStateMachine.evaluate(
                previousFailures, previousSuccesses, service.failureThreshold(),
                service.recoveryThreshold(), incident.isPresent(), result.healthy());

        switch (transition.action()) {
            case OPEN -> store.openIncident(service, checkedAt).ifPresent(incidentId -> {
                notifications.recordOpened(
                        incidentId, service, notificationEnabled, checkedAt);
                recoveries.evaluateOpenIncident(incidentId, service.id(), checkedAt);
            });
            case RESOLVE -> {
                OpenIncident open = incident.orElseThrow();
                if (store.resolveIncident(open.id(), checkedAt)) {
                    notifications.recordRecovered(open, service, notificationEnabled, checkedAt);
                }
            }
            case NONE -> incident.filter(ignored -> !result.healthy()).ifPresent(open -> {
                store.observeIncident(open.id(), checkedAt);
                notifications.recordContinued(open, service, notificationEnabled, checkedAt);
            });
        }
    }
}
