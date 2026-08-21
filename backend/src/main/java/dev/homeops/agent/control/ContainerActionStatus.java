package dev.homeops.agent.control;

public enum ContainerActionStatus {
    REQUESTED,
    APPLIED,
    NOOP,
    DENIED,
    FAILED,
    OUTCOME_UNKNOWN,
    EXPIRED;

    public boolean terminal() {
        return this != REQUESTED;
    }
}
