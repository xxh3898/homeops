package dev.homeops.agent.api;

import dev.homeops.agent.AgentSnapshotService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/agent")
public class AgentSnapshotController {

    private final AgentSnapshotService agentSnapshotService;

    public AgentSnapshotController(AgentSnapshotService agentSnapshotService) {
        this.agentSnapshotService = agentSnapshotService;
    }

    @PostMapping("/snapshots")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AgentSnapshotAcceptedResponse acceptSnapshot(
            @Valid @RequestBody AgentSnapshotRequest request) {
        return agentSnapshotService.accept(request);
    }
}

