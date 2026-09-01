package dev.homeops.recovery.api;

import dev.homeops.recovery.AutomaticRecoveryAction;
import dev.homeops.recovery.AutomaticRecoveryProject;
import dev.homeops.recovery.AutomaticRecoveryTarget;
import dev.homeops.recovery.AutomaticRecoveryWork;
import java.time.Instant;
import java.util.UUID;

public record AgentRecoveryWorkResponse(
        UUID requestId,
        AutomaticRecoveryProject project,
        AutomaticRecoveryTarget target,
        AutomaticRecoveryAction action,
        Instant expiresAt) {

    public static AgentRecoveryWorkResponse from(AutomaticRecoveryWork work) {
        return new AgentRecoveryWorkResponse(
                work.requestId(),
                work.project(),
                work.target(),
                work.action(),
                work.expiresAt());
    }
}
