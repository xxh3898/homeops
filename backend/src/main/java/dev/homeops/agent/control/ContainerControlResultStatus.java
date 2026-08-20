package dev.homeops.agent.control;

public enum ContainerControlResultStatus {
    APPLIED,
    NOOP,
    DENIED,
    FAILED,
    OUTCOME_UNKNOWN,
    EXPIRED
}
