package dev.homeops.recovery;

public enum AutomaticRecoveryStatus {
    REQUESTED,
    DISPATCHED,
    APPLIED,
    SKIPPED,
    FAILED,
    OUTCOME_UNKNOWN,
    EXPIRED;

    public boolean terminal() {
        return this != REQUESTED && this != DISPATCHED;
    }
}
