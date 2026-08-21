package dev.homeops.agent;

import dev.homeops.agent.api.AgentSnapshotRequest.ContainerSnapshot;
import dev.homeops.agent.config.HomeOpsAgentProperties;
import dev.homeops.agent.config.HomeOpsControlProperties;
import dev.homeops.agent.domain.ReceivedAgentSnapshot;
import dev.homeops.system.ContainerIdentifier;
import dev.homeops.system.InvalidContainerIdentifierException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class ContainerControlAuthority {

    private static final String HOMEOPS_PROJECT = "homeops";
    private static final String UNKNOWN_PROJECT = "unknown";

    private final AgentSnapshotService snapshots;
    private final HomeOpsAgentProperties agentProperties;
    private final HomeOpsControlProperties controlProperties;
    private final Clock clock;

    @Autowired
    public ContainerControlAuthority(
            AgentSnapshotService snapshots,
            HomeOpsAgentProperties agentProperties,
            HomeOpsControlProperties controlProperties) {
        this(snapshots, agentProperties, controlProperties, Clock.systemUTC());
    }

    ContainerControlAuthority(
            AgentSnapshotService snapshots,
            HomeOpsAgentProperties agentProperties,
            HomeOpsControlProperties controlProperties,
            Clock clock) {
        this.snapshots = snapshots;
        this.agentProperties = agentProperties;
        this.controlProperties = controlProperties;
        this.clock = clock;
    }

    public Decision evaluate(String rawIdentifier) {
        ContainerIdentifier identifier;
        try {
            identifier = ContainerIdentifier.parse(rawIdentifier);
        } catch (InvalidContainerIdentifierException exception) {
            return Decision.denied(DecisionCode.INVALID_IDENTIFIER);
        }

        ReceivedAgentSnapshot received = snapshots.latest().orElse(null);
        if (received == null) {
            return Decision.denied(DecisionCode.SNAPSHOT_UNAVAILABLE);
        }
        if (AgentFreshness.isStale(
                received.snapshot().capturedAt(),
                received.receivedAt(),
                clock.instant(),
                agentProperties.staleAfter())) {
            return Decision.denied(DecisionCode.STALE_SNAPSHOT);
        }

        List<ContainerSnapshot> matches = received.snapshot().containers().stream()
                .filter(container -> identifier.matches(container.id()))
                .limit(2)
                .toList();
        if (matches.isEmpty()) {
            return Decision.denied(DecisionCode.CONTAINER_NOT_FOUND);
        }
        if (matches.size() > 1) {
            return Decision.denied(DecisionCode.AMBIGUOUS_IDENTIFIER);
        }

        ContainerSnapshot container = matches.getFirst();
        if (!container.managed()) {
            return Decision.denied(DecisionCode.NOT_MANAGED);
        }
        String composeProject = container.composeProject();
        if (composeProject == null
                || composeProject.isBlank()
                || UNKNOWN_PROJECT.equals(composeProject)) {
            return Decision.denied(DecisionCode.PROJECT_UNAVAILABLE);
        }
        if (HOMEOPS_PROJECT.equals(composeProject)) {
            return Decision.denied(DecisionCode.PROTECTED_PROJECT);
        }
        if (!controlProperties.allows(composeProject)) {
            return Decision.denied(DecisionCode.PROJECT_NOT_ALLOWED);
        }
        return Decision.eligible(new Target(identifier.value(), composeProject));
    }

    public enum DecisionCode {
        ELIGIBLE,
        INVALID_IDENTIFIER,
        SNAPSHOT_UNAVAILABLE,
        STALE_SNAPSHOT,
        CONTAINER_NOT_FOUND,
        AMBIGUOUS_IDENTIFIER,
        NOT_MANAGED,
        PROJECT_UNAVAILABLE,
        PROTECTED_PROJECT,
        PROJECT_NOT_ALLOWED
    }

    public record Target(String containerId, String composeProject) {
        public Target {
            containerId = ContainerIdentifier.parse(containerId).value();
            Objects.requireNonNull(composeProject, "composeProject");
            if (composeProject.isBlank()) {
                throw new IllegalArgumentException("Compose project must not be blank");
            }
        }

        @Override
        public String toString() {
            return "Target[containerId=" + containerId + ", composeProject=redacted]";
        }
    }

    public record Decision(DecisionCode code, Target target) {
        public Decision {
            Objects.requireNonNull(code, "code");
            if ((code == DecisionCode.ELIGIBLE) != (target != null)) {
                throw new IllegalArgumentException(
                        "Eligible decisions require exactly one bounded target");
            }
        }

        static Decision eligible(Target target) {
            return new Decision(DecisionCode.ELIGIBLE, target);
        }

        static Decision denied(DecisionCode code) {
            return new Decision(code, null);
        }

        public boolean eligible() {
            return code == DecisionCode.ELIGIBLE;
        }
    }
}
