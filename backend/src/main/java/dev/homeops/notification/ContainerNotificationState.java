package dev.homeops.notification;

import dev.homeops.agent.api.AgentSnapshotRequest.ContainerHealth;
import dev.homeops.agent.api.AgentSnapshotRequest.ContainerState;
import java.time.Instant;
import java.util.UUID;

record ContainerNotificationState(
        UUID id,
        String agentId,
        String logicalIdentityHash,
        String displayName,
        String composeProject,
        String instanceFingerprint,
        boolean notificationsAllowed,
        ContainerState state,
        ContainerHealth health,
        UUID lastSnapshotId,
        Instant lastCapturedAt,
        Instant failureStartedAt,
        UUID activeEpisodeId,
        Instant lastRootCreatedAt) { }
