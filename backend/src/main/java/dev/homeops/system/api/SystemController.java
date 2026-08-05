package dev.homeops.system.api;

import dev.homeops.agent.AgentSnapshotService;
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
    public ContainerInventoryResponse containers() {
        return agentSnapshotService.containerInventory();
    }
}
