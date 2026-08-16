package dev.homeops.metrics;

import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.metrics.HostMetricHistoryStore.HostMetricHistoryRow;
import dev.homeops.system.api.MetricHistoryResponse;
import dev.homeops.system.api.MetricHistoryResponse.MetricHistoryPoint;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MetricHistoryService {
    private final HomeOpsAgentProperties agentProperties;
    private final HostMetricHistoryStore store;
    private final Clock clock;

    @Autowired
    public MetricHistoryService(
            HomeOpsAgentProperties agentProperties,
            HostMetricHistoryStore store) {
        this(agentProperties, store, Clock.systemUTC());
    }

    MetricHistoryService(
            HomeOpsAgentProperties agentProperties,
            HostMetricHistoryStore store,
            Clock clock) {
        this.agentProperties = agentProperties;
        this.store = store;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MetricHistoryResponse history(String requestedPeriod) {
        MetricHistoryPeriod period = MetricHistoryPeriod.parse(requestedPeriod);
        Instant to = period.alignEnd(clock.instant());
        Instant from = to.minus(period.duration());
        List<MetricHistoryPoint> points = store.find(
                        agentProperties.expectedId(),
                        from,
                        to,
                        period.bucketSeconds(),
                        period.maxPoints())
                .stream()
                .map(MetricHistoryService::toPoint)
                .toList();
        return new MetricHistoryResponse(
                period.wireValue(),
                from,
                to,
                period.bucketSeconds(),
                points);
    }

    private static MetricHistoryPoint toPoint(HostMetricHistoryRow row) {
        return new MetricHistoryPoint(
                row.bucketStart(),
                row.sampleCount(),
                row.cpuUsageAverage(),
                row.cpuUsagePeak(),
                row.memoryTotalBytes(),
                row.memoryUsedAverageBytes(),
                row.memoryUsedPeakBytes(),
                row.diskTotalBytes(),
                row.diskUsedBytes());
    }
}
