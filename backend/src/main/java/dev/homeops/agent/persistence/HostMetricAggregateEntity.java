package dev.homeops.agent.persistence;

import dev.homeops.agent.api.AgentSnapshotRequest.HostSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "host_metric_aggregate",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_host_metric_agent_bucket",
                columnNames = {"agent_id", "bucket_start"}))
public class HostMetricAggregateEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "bucket_start", nullable = false)
    private Instant bucketStart;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    @Column(name = "cpu_usage_average", nullable = false)
    private double cpuUsageAverage;

    @Column(name = "cpu_usage_peak", nullable = false)
    private double cpuUsagePeak;

    @Column(name = "memory_total_bytes", nullable = false)
    private long memoryTotalBytes;

    @Column(name = "memory_used_average_bytes", nullable = false)
    private long memoryUsedAverageBytes;

    @Column(name = "memory_used_peak_bytes", nullable = false)
    private long memoryUsedPeakBytes;

    @Column(name = "disk_total_bytes", nullable = false)
    private long diskTotalBytes;

    @Column(name = "disk_used_bytes", nullable = false)
    private long diskUsedBytes;

    protected HostMetricAggregateEntity() {
    }

    private HostMetricAggregateEntity(String agentId, Instant bucketStart) {
        this.agentId = agentId;
        this.bucketStart = bucketStart;
    }

    public static HostMetricAggregateEntity create(
            String agentId,
            Instant bucketStart,
            HostSnapshot sample) {
        HostMetricAggregateEntity aggregate =
                new HostMetricAggregateEntity(agentId, bucketStart);
        aggregate.addSample(sample);
        return aggregate;
    }

    public void addSample(HostSnapshot sample) {
        long previousCount = sampleCount;
        long nextCount = previousCount + 1;
        cpuUsageAverage = weightedAverage(
                cpuUsageAverage,
                sample.cpuUsagePercent(),
                previousCount,
                nextCount);
        memoryUsedAverageBytes = weightedAverage(
                memoryUsedAverageBytes,
                sample.memoryUsedBytes(),
                previousCount,
                nextCount);
        sampleCount++;
        cpuUsagePeak = Math.max(cpuUsagePeak, sample.cpuUsagePercent());
        memoryUsedPeakBytes = Math.max(
                memoryUsedPeakBytes,
                sample.memoryUsedBytes());
        memoryTotalBytes = sample.memoryTotalBytes();
        diskTotalBytes = sample.diskTotalBytes();
        diskUsedBytes = sample.diskUsedBytes();
    }

    private static double weightedAverage(
            double current,
            double value,
            long previousCount,
            long nextCount) {
        return ((current * previousCount) + value) / nextCount;
    }

    private static long weightedAverage(
            long current,
            long value,
            long previousCount,
            long nextCount) {
        return Math.round(
                (((double) current * previousCount) + value) / nextCount);
    }
}
