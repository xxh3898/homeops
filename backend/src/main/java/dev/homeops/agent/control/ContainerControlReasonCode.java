package dev.homeops.agent.control;

import java.util.EnumSet;

public enum ContainerControlReasonCode {
    APPLIED,
    ALREADY_RUNNING,
    ALREADY_STOPPED,
    CONTAINER_NOT_FOUND,
    AMBIGUOUS_IDENTIFIER,
    NOT_MANAGED,
    PROJECT_MISMATCH,
    PROTECTED_PROJECT,
    COMPOSE_SERVICE_UNAVAILABLE,
    PROTECTED_SERVICE,
    WRITABLE_MOUNT,
    MOUNT_PROTECTION_UNAVAILABLE,
    DOCKER_UNAVAILABLE,
    DOCKER_REJECTED,
    DOCKER_OUTCOME_UNKNOWN,
    RESULT_UNAVAILABLE,
    WORK_EXPIRED;

    private static final EnumSet<ContainerControlReasonCode> NOOP_REASONS =
            EnumSet.of(ALREADY_RUNNING, ALREADY_STOPPED);
    private static final EnumSet<ContainerControlReasonCode> DENIED_REASONS =
            EnumSet.of(
                    CONTAINER_NOT_FOUND,
                    AMBIGUOUS_IDENTIFIER,
                    NOT_MANAGED,
                    PROJECT_MISMATCH,
                    PROTECTED_PROJECT,
                    COMPOSE_SERVICE_UNAVAILABLE,
                    PROTECTED_SERVICE,
                    WRITABLE_MOUNT,
                    MOUNT_PROTECTION_UNAVAILABLE);
    private static final EnumSet<ContainerControlReasonCode> FAILED_REASONS =
            EnumSet.of(DOCKER_UNAVAILABLE, DOCKER_REJECTED);

    public boolean isValidFor(ContainerControlResultStatus status) {
        return switch (status) {
            case APPLIED -> this == APPLIED;
            case NOOP -> NOOP_REASONS.contains(this);
            case DENIED -> DENIED_REASONS.contains(this);
            case FAILED -> FAILED_REASONS.contains(this);
            case OUTCOME_UNKNOWN -> this == DOCKER_OUTCOME_UNKNOWN
                    || this == RESULT_UNAVAILABLE;
            case EXPIRED -> this == WORK_EXPIRED;
        };
    }
}
