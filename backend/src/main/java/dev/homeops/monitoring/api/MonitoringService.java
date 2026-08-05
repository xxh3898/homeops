package dev.homeops.monitoring.api;

import dev.homeops.monitoring.MonitoredServiceStore;
import dev.homeops.monitoring.SafeServiceUrlPolicy;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MonitoringService {
    private final MonitoredServiceStore store;
    private final SafeServiceUrlPolicy policy;
    public MonitoringService(MonitoredServiceStore store, SafeServiceUrlPolicy policy) {
        this.store = store;
        this.policy = policy;
    }

    public MonitoredServiceResponse create(MonitoredServiceRequest request) {
        policy.validate(request.url());
        return store.create(request);
    }

    public List<MonitoredServiceResponse> list() {
        return store.list();
    }

    public List<IncidentResponse> recentIncidents() {
        return store.recentIncidents();
    }

    public List<ServiceStatusResponse> currentStatuses() {
        return store.currentStatuses();
    }
}
