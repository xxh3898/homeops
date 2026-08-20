package dev.homeops.agent;

import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.agent.persistence.AgentStatusStore;
import dev.homeops.agent.persistence.AgentStatusStore.AgentStatusSnapshot;
import dev.homeops.notification.AgentLifecycleNotificationProducer;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AgentFreshnessNotificationMonitor {
    private final HomeOpsAgentProperties agentProperties;
    private final AgentStatusStore statusStore;
    private final AgentLifecycleNotificationProducer notifications;
    private final Clock clock;

    @Autowired
    public AgentFreshnessNotificationMonitor(
            HomeOpsAgentProperties agentProperties,
            AgentStatusStore statusStore,
            AgentLifecycleNotificationProducer notifications) {
        this(agentProperties, statusStore, notifications, Clock.systemUTC());
    }

    AgentFreshnessNotificationMonitor(
            HomeOpsAgentProperties agentProperties,
            AgentStatusStore statusStore,
            AgentLifecycleNotificationProducer notifications,
            Clock clock) {
        this.agentProperties = agentProperties;
        this.statusStore = statusStore;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${homeops.notifications.agent.freshness-check-delay}")
    @Transactional
    public void checkFreshness() {
        Instant observedAt = clock.instant();
        statusStore.findForUpdate(agentProperties.expectedId())
                .filter(AgentFreshnessNotificationMonitor::hasCompleteEpisodeIdentity)
                .filter(status -> AgentFreshness.isStale(
                        status.lastCapturedAt(),
                        status.lastSeenAt(),
                        observedAt,
                        agentProperties.staleAfter()))
                .ifPresent(status -> recordStale(status, observedAt));
    }

    private void recordStale(AgentStatusSnapshot status, Instant observedAt) {
        notifications.recordStale(
                status.lastSnapshotId(),
                status.agentId(),
                status.agentVersion(),
                AgentFreshness.staleSince(status.lastCapturedAt(), status.lastSeenAt()),
                observedAt);
    }

    private static boolean hasCompleteEpisodeIdentity(AgentStatusSnapshot status) {
        return status.lastSnapshotId() != null
                && status.lastCapturedAt() != null
                && status.lastSeenAt() != null;
    }
}
