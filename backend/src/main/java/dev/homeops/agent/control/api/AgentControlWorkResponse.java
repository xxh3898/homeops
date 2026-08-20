package dev.homeops.agent.control.api;

import dev.homeops.agent.control.ContainerControlOperation;
import dev.homeops.agent.control.ContainerControlWork;
import java.time.Instant;
import java.util.UUID;

public record AgentControlWorkResponse(
        UUID requestId,
        String containerId,
        String composeProject,
        ContainerControlOperation operation,
        Instant expiresAt) {

    public static AgentControlWorkResponse from(ContainerControlWork work) {
        return new AgentControlWorkResponse(
                work.requestId(),
                work.containerId(),
                work.composeProject(),
                work.operation(),
                work.expiresAt());
    }
}
