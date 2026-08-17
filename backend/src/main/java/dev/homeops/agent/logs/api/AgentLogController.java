package dev.homeops.agent.logs.api;

import dev.homeops.agent.logs.ContainerLogBroker;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/agent")
public class AgentLogController {

    private final ContainerLogBroker broker;

    public AgentLogController(ContainerLogBroker broker) {
        this.broker = broker;
    }

    @GetMapping("/log-requests/next")
    public ResponseEntity<AgentLogWorkResponse> nextWork() {
        return broker.claimNext()
                .map(AgentLogWorkResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/log-results")
    public ResponseEntity<Void> acceptResult(
            @Valid @RequestBody AgentLogResultRequest request) {
        broker.complete(request);
        return ResponseEntity.noContent().build();
    }
}
