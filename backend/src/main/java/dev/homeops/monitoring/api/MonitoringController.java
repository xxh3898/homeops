package dev.homeops.monitoring.api;

import dev.homeops.monitoring.MonitoredServiceStore;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/services")
public class MonitoringController {
    private final MonitoredServiceStore store;
    public MonitoringController(MonitoredServiceStore store) { this.store = store; }
    @GetMapping public List<MonitoredServiceResponse> list() { return store.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public MonitoredServiceResponse create(@Valid @RequestBody MonitoredServiceRequest request) { return store.create(request); }
}
