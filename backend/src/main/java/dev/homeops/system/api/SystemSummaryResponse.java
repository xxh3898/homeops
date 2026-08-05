package dev.homeops.system.api;

import java.time.Instant;

public record SystemSummaryResponse(
        String agentStatus,
        Instant lastUpdatedAt,
        boolean stale,
        HostMetricView host,
        DockerSummaryView docker) {

    public record HostMetricView(
            double cpuUsagePercent,
            long memoryTotalBytes,
            long memoryUsedBytes,
            long diskTotalBytes,
            long diskUsedBytes,
            long uptimeSeconds) {
    }

    public record DockerSummaryView(
            int total,
            int running,
            int notRunning,
            int unhealthy) {
    }
}
