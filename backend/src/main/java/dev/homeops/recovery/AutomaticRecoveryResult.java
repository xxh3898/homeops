package dev.homeops.recovery;

import java.time.Instant;
import java.util.Objects;

public record AutomaticRecoveryResult(
        AutomaticRecoveryResultStatus status,
        AutomaticRecoveryReasonCode reasonCode,
        Instant startedAt,
        Instant finishedAt,
        AutomaticRecoveryHealth preHealth,
        AutomaticRecoveryHealth postHealth,
        int restartCount) {

    public AutomaticRecoveryResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(preHealth, "preHealth");
        Objects.requireNonNull(postHealth, "postHealth");
        if (!reasonCode.isValidFor(status)) {
            throw new IllegalArgumentException("Automatic recovery result status and reason are inconsistent");
        }
        if (finishedAt.isBefore(startedAt) || restartCount < 0 || restartCount > 1) {
            throw new IllegalArgumentException("Automatic recovery result evidence is invalid");
        }
        if (status == AutomaticRecoveryResultStatus.APPLIED
                && (restartCount != 1 || postHealth != AutomaticRecoveryHealth.UP)) {
            throw new IllegalArgumentException("Applied automatic recovery evidence is invalid");
        }
        if ((status == AutomaticRecoveryResultStatus.NOOP
                || status == AutomaticRecoveryResultStatus.EXPIRED)
                && restartCount != 0) {
            throw new IllegalArgumentException("Non-mutating automatic recovery result is invalid");
        }
    }
}
