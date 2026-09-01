package dev.homeops.recovery.api;

import dev.homeops.recovery.AutomaticRecoveryBroker;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/agent")
public class AgentRecoveryController {
    private final AutomaticRecoveryBroker broker;

    public AgentRecoveryController(AutomaticRecoveryBroker broker) {
        this.broker = broker;
    }

    @GetMapping("/recovery-requests/next")
    public ResponseEntity<AgentRecoveryWorkResponse> nextWork() {
        return broker.claimNext()
                .map(AgentRecoveryWorkResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/recovery-results")
    public ResponseEntity<Void> acceptResult(
            @Valid @RequestBody AgentRecoveryResultRequest request) {
        broker.complete(request);
        return ResponseEntity.noContent().build();
    }
}
