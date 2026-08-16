package dev.homeops.system.api;

import java.time.Instant;
import java.util.List;

public record MetricHistoryResponse(
        String period,
        Instant from,
        Instant to,
        long bucketSeconds,
        List<MetricHistoryPoint> points) {

    public MetricHistoryResponse {
        points = List.copyOf(points);
    }

    public record MetricHistoryPoint(
            Instant bucketStart,
            long sampleCount,
            double cpuUsageAverage,
            double cpuUsagePeak,
            long memoryTotalBytes,
            long memoryUsedAverageBytes,
            long memoryUsedPeakBytes,
            long diskTotalBytes,
            long diskUsedBytes) {
    }
}
