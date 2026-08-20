package dev.homeops.agent.api;

import dev.homeops.common.validation.NoPostgresqlNul;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentSnapshotRequest(
        @NotNull UUID snapshotId,
        @NotBlank
        @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
        String agentId,
        @NotBlank @Size(max = 64) @NoPostgresqlNul String agentVersion,
        @NotNull Instant capturedAt,
        Boolean supportsContainerLogs,
        @NotNull @Valid HostSnapshot host,
        @NotNull @Size(max = 256) List<@NotNull @Valid ContainerSnapshot> containers) {

    public AgentSnapshotRequest {
        supportsContainerLogs = Boolean.TRUE.equals(supportsContainerLogs);
        containers = containers == null ? null : List.copyOf(containers);
    }

    public AgentSnapshotRequest(
            UUID snapshotId,
            String agentId,
            String agentVersion,
            Instant capturedAt,
            HostSnapshot host,
            List<ContainerSnapshot> containers) {
        this(snapshotId, agentId, agentVersion, capturedAt, false, host, containers);
    }

    public record HostSnapshot(
            @DecimalMin("0.0") @DecimalMax("100.0") double cpuUsagePercent,
            @Positive long memoryTotalBytes,
            @PositiveOrZero long memoryUsedBytes,
            @Positive long diskTotalBytes,
            @PositiveOrZero long diskUsedBytes,
            @PositiveOrZero long uptimeSeconds) {
    }

    public record ContainerSnapshot(
            @NotBlank @Pattern(regexp = "[0-9a-f]{12,64}") String id,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 128) String composeProject,
            @NotBlank @Size(max = 512) String image,
            @NotNull ContainerState state,
            @NotNull ContainerHealth health,
            @Size(max = 512) String status,
            Instant startedAt,
            @PositiveOrZero long restartCount,
            @DecimalMin("0.0") Double cpuUsagePercent,
            @PositiveOrZero Long memoryUsageBytes,
            @Positive Long memoryLimitBytes,
            @NotNull @Size(max = 64) List<@NotNull @Valid ContainerPort> ports,
            boolean managed,
            Boolean logsAllowed,
            Boolean notificationsAllowed) {

        public ContainerSnapshot {
            logsAllowed = Boolean.TRUE.equals(logsAllowed);
            notificationsAllowed = Boolean.TRUE.equals(notificationsAllowed);
            ports = ports == null ? null : List.copyOf(ports);
        }

        public ContainerSnapshot(
                String id,
                String name,
                String composeProject,
                String image,
                ContainerState state,
                ContainerHealth health,
                String status,
                Instant startedAt,
                long restartCount,
                Double cpuUsagePercent,
                Long memoryUsageBytes,
                Long memoryLimitBytes,
                List<ContainerPort> ports,
                boolean managed,
                Boolean logsAllowed) {
            this(
                    id,
                    name,
                    composeProject,
                    image,
                    state,
                    health,
                    status,
                    startedAt,
                    restartCount,
                    cpuUsagePercent,
                    memoryUsageBytes,
                    memoryLimitBytes,
                    ports,
                    managed,
                    logsAllowed,
                    false);
        }

        public ContainerSnapshot(
                String id,
                String name,
                String composeProject,
                String image,
                ContainerState state,
                ContainerHealth health,
                String status,
                Instant startedAt,
                long restartCount,
                Double cpuUsagePercent,
                Long memoryUsageBytes,
                Long memoryLimitBytes,
                List<ContainerPort> ports,
                boolean managed) {
            this(
                    id,
                    name,
                    composeProject,
                    image,
                    state,
                    health,
                    status,
                    startedAt,
                    restartCount,
                    cpuUsagePercent,
                    memoryUsageBytes,
                    memoryLimitBytes,
                    ports,
                    managed,
                    false,
                    false);
        }
    }

    public record ContainerPort(
            @Min(1) @Max(65535) int privatePort,
            @Min(1) @Max(65535) Integer publicPort,
            @NotNull PortType type) {
    }

    public enum ContainerState {
        CREATED,
        RUNNING,
        PAUSED,
        RESTARTING,
        REMOVING,
        EXITED,
        DEAD,
        UNKNOWN
    }

    public enum ContainerHealth {
        HEALTHY,
        UNHEALTHY,
        STARTING,
        NONE,
        UNKNOWN
    }

    public enum PortType {
        TCP,
        UDP,
        SCTP,
        UNKNOWN
    }
}
