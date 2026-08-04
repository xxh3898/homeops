package dev.homeops.system.api;

import dev.homeops.agent.AgentSnapshotService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SystemController {

    private final AgentSnapshotService agentSnapshotService;

    public SystemController(AgentSnapshotService agentSnapshotService) {
        this.agentSnapshotService = agentSnapshotService;
    }

    @GetMapping("/system/summary")
    public SystemSummaryResponse summary() {
        return agentSnapshotService.summary();
    }

    @GetMapping("/containers")
    public List<ContainerView> containers() {
        return agentSnapshotService.containers();
    }
}

