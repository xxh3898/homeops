package dev.homeops.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_status")
public class AgentStatusEntity {

    @Id
    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "agent_version", nullable = false, length = 64)
    private String agentVersion;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "last_snapshot_id")
    private UUID lastSnapshotId;

    @Column(name = "last_captured_at")
    private Instant lastCapturedAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    protected AgentStatusEntity() {
    }

    private AgentStatusEntity(String agentId) {
        this.agentId = agentId;
        this.agentVersion = "unknown";
        this.status = "UNKNOWN";
    }

    public static AgentStatusEntity create(String agentId) {
        return new AgentStatusEntity(agentId);
    }

    public void recordSnapshot(
            UUID snapshotId,
            String version,
            Instant capturedAt,
            Instant receivedAt) {
        this.lastSnapshotId = snapshotId;
        this.agentVersion = version;
        this.lastCapturedAt = capturedAt;
        this.lastSeenAt = receivedAt;
        this.status = "CONNECTED";
    }

    public String getAgentId() {
        return agentId;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public String getStatus() {
        return status;
    }

    public UUID getLastSnapshotId() {
        return lastSnapshotId;
    }

    public Instant getLastCapturedAt() {
        return lastCapturedAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
