package dev.homeops.monitoring.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/services")
public class MonitoringController {
    private final MonitoringService service;

    public MonitoringController(MonitoringService service) {
        this.service = service;
    }

    @GetMapping
    public List<MonitoredServiceResponse> list() {
        return service.list();
    }

    @GetMapping("/incidents")
    public List<IncidentResponse> incidents() {
        return service.recentIncidents();
    }

    @GetMapping("/status")
    public List<ServiceStatusResponse> statuses() {
        return service.currentStatuses();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MonitoredServiceResponse create(@Valid @RequestBody MonitoredServiceRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{serviceId}/notification")
    public MonitoredServiceNotificationResponse updateNotificationAuthority(
            @PathVariable UUID serviceId,
            @Valid @RequestBody MonitoredServiceNotificationRequest request) {
        return service.updateNotificationAuthority(serviceId, request.enabled());
    }
}
