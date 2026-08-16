package dev.homeops.metrics;

import dev.homeops.common.PostgresqlTimestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HostMetricHistoryStore {
    private final JdbcTemplate jdbc;

    public HostMetricHistoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<HostMetricHistoryRow> find(
            String agentId,
            Instant from,
            Instant to,
            long bucketSeconds,
            int maxPoints) {
        return jdbc.query("""
                WITH bounded AS (
                    SELECT bucket_start,
                           sample_count,
                           cpu_usage_average,
                           cpu_usage_peak,
                           memory_total_bytes,
                           memory_used_average_bytes,
                           memory_used_peak_bytes,
                           disk_total_bytes,
                           disk_used_bytes,
                           to_timestamp(
                               floor(extract(epoch FROM bucket_start) / CAST(? AS double precision))
                               * CAST(? AS double precision)
                           ) AS output_bucket
                    FROM host_metric_aggregate
                    WHERE agent_id = ?
                      AND bucket_start >= ?
                      AND bucket_start < ?
                ), aggregated AS (
                    SELECT output_bucket,
                           SUM(sample_count)::bigint AS sample_count,
                           SUM(cpu_usage_average * sample_count::double precision)
                               / NULLIF(SUM(sample_count), 0) AS cpu_usage_average,
                           MAX(cpu_usage_peak) AS cpu_usage_peak,
                           ROUND(
                               SUM(memory_used_average_bytes::numeric * sample_count)
                               / NULLIF(SUM(sample_count), 0)
                           )::bigint AS memory_used_average_bytes,
                           MAX(memory_used_peak_bytes) AS memory_used_peak_bytes
                    FROM bounded
                    GROUP BY output_bucket
                ), latest AS (
                    SELECT DISTINCT ON (output_bucket)
                           output_bucket,
                           memory_total_bytes,
                           disk_total_bytes,
                           disk_used_bytes
                    FROM bounded
                    ORDER BY output_bucket, bucket_start DESC
                )
                SELECT aggregated.output_bucket AS bucket_start,
                       aggregated.sample_count,
                       aggregated.cpu_usage_average,
                       aggregated.cpu_usage_peak,
                       latest.memory_total_bytes,
                       aggregated.memory_used_average_bytes,
                       aggregated.memory_used_peak_bytes,
                       latest.disk_total_bytes,
                       latest.disk_used_bytes
                FROM aggregated
                JOIN latest USING (output_bucket)
                ORDER BY aggregated.output_bucket ASC
                LIMIT ?
                """, (row, index) -> new HostMetricHistoryRow(
                row.getTimestamp("bucket_start").toInstant(),
                row.getLong("sample_count"),
                row.getDouble("cpu_usage_average"),
                row.getDouble("cpu_usage_peak"),
                row.getLong("memory_total_bytes"),
                row.getLong("memory_used_average_bytes"),
                row.getLong("memory_used_peak_bytes"),
                row.getLong("disk_total_bytes"),
                row.getLong("disk_used_bytes")),
                bucketSeconds,
                bucketSeconds,
                agentId,
                PostgresqlTimestamp.toTimestamp(from),
                PostgresqlTimestamp.toTimestamp(to),
                maxPoints);
    }

    public record HostMetricHistoryRow(
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
