package dev.homeops.ingestion.api;

import dev.homeops.ingestion.IngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/ingestion")
public class IngestionController {
    private final IngestionService service;
    public IngestionController(IngestionService service) { this.service = service; }

    @PostMapping("/deployments")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestionAcceptedResponse acceptDeployment(@Valid @RequestBody DeploymentIngestionRequest request) {
        return service.acceptDeployment(request);
    }

    @PostMapping("/backups")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestionAcceptedResponse acceptBackup(@Valid @RequestBody BackupIngestionRequest request) {
        return service.acceptBackup(request);
    }
}
