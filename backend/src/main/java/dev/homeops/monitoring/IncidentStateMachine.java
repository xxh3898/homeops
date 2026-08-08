package dev.homeops.monitoring;

import java.util.Objects;

public final class IncidentStateMachine {

    private IncidentStateMachine() {
    }

    public static Transition evaluate(
            int consecutiveFailures,
            int consecutiveSuccesses,
            int failureThreshold,
            int recoveryThreshold,
            boolean incidentOpen,
            boolean successful) {
        if (failureThreshold < 1 || recoveryThreshold < 1) {
            throw new IllegalArgumentException("Thresholds must be positive");
        }
        if (successful) {
            int successes = Math.addExact(consecutiveSuccesses, 1);
            if (incidentOpen && successes >= recoveryThreshold) {
                return new Transition(0, successes, Action.RESOLVE);
            }
            return new Transition(0, successes, Action.NONE);
        }

        int failures = Math.addExact(consecutiveFailures, 1);
        if (!incidentOpen && failures >= failureThreshold) {
            return new Transition(failures, 0, Action.OPEN);
        }
        return new Transition(failures, 0, Action.NONE);
    }

    public record Transition(int consecutiveFailures, int consecutiveSuccesses, Action action) {
        public Transition {
            if (consecutiveFailures < 0 || consecutiveSuccesses < 0) {
                throw new IllegalArgumentException("Counters must not be negative");
            }
            Objects.requireNonNull(action, "action");
        }
    }

    public enum Action { NONE, OPEN, RESOLVE }
}
