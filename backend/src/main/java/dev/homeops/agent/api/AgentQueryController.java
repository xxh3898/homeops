package dev.homeops.agent.api;

import dev.homeops.agent.AgentSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentQueryController {

    private final AgentSnapshotService agentSnapshotService;

    public AgentQueryController(AgentSnapshotService agentSnapshotService) {
        this.agentSnapshotService = agentSnapshotService;
    }

    @GetMapping("/status")
    public AgentStatusResponse status() {
        return agentSnapshotService.status();
    }
}

