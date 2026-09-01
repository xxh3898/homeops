package dev.homeops.recovery.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import dev.homeops.recovery.AutomaticRecoveryHealth;
import dev.homeops.recovery.AutomaticRecoveryReasonCode;
import dev.homeops.recovery.AutomaticRecoveryResultStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record AgentRecoveryResultRequest(
        @NotNull UUID requestId,
        @NotNull AutomaticRecoveryResultStatus status,
        @NotNull AutomaticRecoveryReasonCode reasonCode,
        @NotNull Instant startedAt,
        @NotNull Instant finishedAt,
        @NotNull AutomaticRecoveryHealth preHealth,
        @NotNull AutomaticRecoveryHealth postHealth,
        @Min(0) @Max(1) int restartCount) {

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown automatic recovery result field");
    }
}
