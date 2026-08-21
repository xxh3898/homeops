package dev.homeops.agent.control.api;

import dev.homeops.agent.control.ContainerActionException;
import dev.homeops.agent.control.ContainerActionIdempotencyKey;
import dev.homeops.agent.control.ContainerActionService;
import dev.homeops.agent.control.ContainerActionStatus;
import dev.homeops.security.ContainerControlOriginGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ContainerActionController {
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final ContainerActionService service;
    private final ContainerControlOriginGuard originGuard;

    public ContainerActionController(
            ContainerActionService service,
            ContainerControlOriginGuard originGuard) {
        this.service = service;
        this.originGuard = originGuard;
    }

    @PostMapping("/api/v1/containers/{containerId}/actions")
    public ResponseEntity<ContainerActionResponse> create(
            @PathVariable String containerId,
            @Valid @RequestBody ContainerActionRequest request,
            HttpServletRequest servletRequest,
            Principal principal) {
        originGuard.requireSameOrigin(servletRequest);
        ContainerActionIdempotencyKey idempotencyKey = ContainerActionIdempotencyKey.parse(
                singleHeader(servletRequest, IDEMPOTENCY_HEADER));
        ContainerActionService.Submission submission = service.submit(
                containerId,
                request.operation(),
                request.confirmation(),
                idempotencyKey,
                principal.getName());
        HttpStatus status = submission.created()
                || submission.record().status() == ContainerActionStatus.REQUESTED
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(ContainerActionResponse.from(submission.record()));
    }

    @GetMapping("/api/v1/container-actions/{operationId}")
    public ContainerActionResponse find(@PathVariable UUID operationId) {
        return ContainerActionResponse.from(service.find(operationId));
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()) {
            throw ContainerActionException.invalidRequest();
        }
        return values.getFirst();
    }
}
