package dev.homeops.recovery;

import java.util.EnumSet;

public enum AutomaticRecoveryReasonCode {
    TARGET_UNMAPPED,
    AUTHORITY_DISABLED,
    COOLDOWN_ACTIVE,
    INCIDENT_NOT_OPEN,
    REQUEST_EXPIRED,
    CAPABILITY_UNAVAILABLE,
    BROKER_BUSY,
    RECOVERY_APPLIED,
    RECOVERY_INPUT_INVALID,
    RECOVERY_LOCKED,
    RECOVERY_LOCK_INVALID,
    RECOVERY_LOCK_RELEASE_FAILED,
    RECOVERY_TARGET_INVALID,
    RECOVERY_TARGET_UNAVAILABLE,
    RECOVERY_IDENTITY_CHANGED,
    RECOVERY_POST_HEALTH_FAILED,
    RECOVERY_RESTART_UNCONFIRMED,
    RECOVERY_FAILED,
    CAPABILITY_RESULT_INVALID,
    CAPABILITY_TIMEOUT,
    RESULT_UNAVAILABLE,
    WORK_EXPIRED;

    private static final EnumSet<AutomaticRecoveryReasonCode> NOOP_REASONS =
            EnumSet.of(
                    RECOVERY_INPUT_INVALID,
                    RECOVERY_LOCKED,
                    RECOVERY_LOCK_INVALID,
                    RECOVERY_TARGET_INVALID,
                    RECOVERY_TARGET_UNAVAILABLE);
    private static final EnumSet<AutomaticRecoveryReasonCode> FAILED_REASONS =
            EnumSet.of(
                    RECOVERY_LOCK_RELEASE_FAILED,
                    RECOVERY_IDENTITY_CHANGED,
                    RECOVERY_POST_HEALTH_FAILED,
                    RECOVERY_FAILED,
                    CAPABILITY_UNAVAILABLE);
    private static final EnumSet<AutomaticRecoveryReasonCode> UNKNOWN_REASONS =
            EnumSet.of(
                    RECOVERY_RESTART_UNCONFIRMED,
                    CAPABILITY_RESULT_INVALID,
                    CAPABILITY_TIMEOUT,
                    RESULT_UNAVAILABLE);

    public boolean isValidFor(AutomaticRecoveryResultStatus status) {
        return switch (status) {
            case APPLIED -> this == RECOVERY_APPLIED;
            case NOOP -> NOOP_REASONS.contains(this);
            case FAILED -> FAILED_REASONS.contains(this);
            case OUTCOME_UNKNOWN -> UNKNOWN_REASONS.contains(this);
            case EXPIRED -> this == WORK_EXPIRED;
        };
    }
}
