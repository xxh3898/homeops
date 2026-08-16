package dev.homeops.system.api;

import dev.homeops.agent.AgentSnapshotService;
import dev.homeops.metrics.MetricHistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SystemController {

    private final AgentSnapshotService agentSnapshotService;
    private final MetricHistoryService metricHistoryService;

    public SystemController(
            AgentSnapshotService agentSnapshotService,
            MetricHistoryService metricHistoryService) {
        this.agentSnapshotService = agentSnapshotService;
        this.metricHistoryService = metricHistoryService;
    }

    @GetMapping("/system/summary")
    public SystemSummaryResponse summary() {
        return agentSnapshotService.summary();
    }

    @GetMapping("/system/metrics/history")
    public MetricHistoryResponse metricHistory(
            @RequestParam(name = "period", required = false) String period) {
        return metricHistoryService.history(period);
    }

    @GetMapping("/containers")
    public ContainerInventoryResponse containers() {
        return agentSnapshotService.containerInventory();
    }
}
