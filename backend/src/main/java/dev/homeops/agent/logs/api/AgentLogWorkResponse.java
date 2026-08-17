package dev.homeops.agent.logs.api;

import dev.homeops.agent.logs.ContainerLogWork;
import java.util.UUID;

public record AgentLogWorkResponse(
        UUID requestId,
        String containerId,
        int tail) {

    public static AgentLogWorkResponse from(ContainerLogWork work) {
        return new AgentLogWorkResponse(
                work.requestId(),
                work.containerId(),
                work.tail());
    }
}
