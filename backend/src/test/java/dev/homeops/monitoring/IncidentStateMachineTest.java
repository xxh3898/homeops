package dev.homeops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IncidentStateMachineTest {

    @Test
    void should_openOnly_when_failureThresholdIsReached() {
        var before = IncidentStateMachine.evaluate(1, 0, 3, 2, false, false);
        var reached = IncidentStateMachine.evaluate(2, 0, 3, 2, false, false);

        assertThat(before.action()).isEqualTo(IncidentStateMachine.Action.NONE);
        assertThat(reached.action()).isEqualTo(IncidentStateMachine.Action.OPEN);
        assertThat(reached.consecutiveFailures()).isEqualTo(3);
    }

    @Test
    void should_resolveOnly_when_recoveryThresholdIsReached() {
        var before = IncidentStateMachine.evaluate(0, 0, 3, 2, true, true);
        var reached = IncidentStateMachine.evaluate(0, 1, 3, 2, true, true);

        assertThat(before.action()).isEqualTo(IncidentStateMachine.Action.NONE);
        assertThat(reached.action()).isEqualTo(IncidentStateMachine.Action.RESOLVE);
    }

    @Test
    void should_resetOppositeCounter_when_checkOutcomeChanges() {
        var failure = IncidentStateMachine.evaluate(0, 4, 3, 2, false, false);
        var success = IncidentStateMachine.evaluate(5, 0, 3, 2, true, true);

        assertThat(failure.consecutiveSuccesses()).isZero();
        assertThat(success.consecutiveFailures()).isZero();
    }

    @Test
    void should_rejectNonPositiveThreshold() {
        assertThatThrownBy(() -> IncidentStateMachine.evaluate(0, 0, 0, 1, false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
