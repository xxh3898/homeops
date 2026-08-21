package dev.homeops.agent.control.api;

import dev.homeops.agent.control.ContainerControlBroker;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/agent")
public class AgentControlController {

    private final ContainerControlBroker broker;

    public AgentControlController(ContainerControlBroker broker) {
        this.broker = broker;
    }

    @GetMapping("/control-requests/next")
    public ResponseEntity<AgentControlWorkResponse> nextWork() {
        return broker.claimNext()
                .map(AgentControlWorkResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/control-results")
    public ResponseEntity<Void> acceptResult(
            @Valid @RequestBody AgentControlResultRequest request) {
        broker.complete(request);
        return ResponseEntity.noContent().build();
    }
}
