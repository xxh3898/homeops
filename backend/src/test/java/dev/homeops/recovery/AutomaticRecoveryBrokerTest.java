package dev.homeops.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homeops.recovery.api.AgentRecoveryResultRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutomaticRecoveryBrokerTest {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString(
            "10000000-0000-4000-8000-000000000119");

    private MutableClock clock;
    private AutomaticRecoveryBroker broker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        broker = new AutomaticRecoveryBroker(clock, Duration.ZERO);
    }

    @Test
    void should_deliverBoundedWorkOnceAndCompleteIdempotently_when_resultIsValid() {
        AutomaticRecoveryRequestTicket ticket = broker.enqueue(
                REQUEST_ID,
                AutomaticRecoveryProject.RHAOMI,
                AutomaticRecoveryTarget.BACKEND,
                AutomaticRecoveryAction.RESTART);

        AutomaticRecoveryWork work = broker.claimNext().orElseThrow();
        assertThat(work.requestId()).isEqualTo(REQUEST_ID);
        assertThat(work.project()).isEqualTo(AutomaticRecoveryProject.RHAOMI);
        assertThat(work.target()).isEqualTo(AutomaticRecoveryTarget.BACKEND);
        assertThat(work.action()).isEqualTo(AutomaticRecoveryAction.RESTART);
        assertThat(work.expiresAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(broker.claimNext()).isEmpty();

        clock.advance(Duration.ofSeconds(2));
        AgentRecoveryResultRequest result = appliedResult(NOW.plusSeconds(1), NOW.plusSeconds(2));
        broker.complete(result);
        broker.complete(result);

        assertThat(ticket.result().join().status())
                .isEqualTo(AutomaticRecoveryResultStatus.APPLIED);
        assertThat(ticket.result().join().restartCount()).isOne();
        assertThat(broker.activeRequestCount()).isZero();
        assertThat(broker.claimNext()).isEmpty();
    }

    @Test
    void should_rejectInconsistentResultWithoutConsumingRequest_when_statusReasonDoNotMatch() {
        broker.enqueue(
                REQUEST_ID,
                AutomaticRecoveryProject.RHAOMI,
                AutomaticRecoveryTarget.RHAOMI_WEB,
                AutomaticRecoveryAction.RESTART);
        broker.claimNext().orElseThrow();
        AgentRecoveryResultRequest invalid = new AgentRecoveryResultRequest(
                REQUEST_ID,
                AutomaticRecoveryResultStatus.APPLIED,
                AutomaticRecoveryReasonCode.RECOVERY_FAILED,
                NOW,
                NOW,
                AutomaticRecoveryHealth.DOWN,
                AutomaticRecoveryHealth.DOWN,
                0);

        assertThatThrownBy(() -> broker.complete(invalid))
                .isInstanceOf(AutomaticRecoveryResultRejectedException.class);
        assertThat(broker.activeRequestCount()).isOne();
        assertThat(broker.claimNext()).isEmpty();
    }

    @Test
    void should_expireUnclaimedWorkWithoutRequeue_when_startDeadlinePasses() {
        AutomaticRecoveryRequestTicket ticket = broker.enqueue(
                REQUEST_ID,
                AutomaticRecoveryProject.RHAOMI,
                AutomaticRecoveryTarget.BACKEND,
                AutomaticRecoveryAction.RESTART);

        clock.advance(AutomaticRecoveryBroker.REQUEST_TTL);

        assertThat(broker.claimNext()).isEmpty();
        assertThat(ticket.result().join().status())
                .isEqualTo(AutomaticRecoveryResultStatus.EXPIRED);
        assertThat(ticket.result().join().reasonCode())
                .isEqualTo(AutomaticRecoveryReasonCode.WORK_EXPIRED);
        assertThat(ticket.result().join().restartCount()).isZero();
    }

    @Test
    void should_recordOutcomeUnknownWithoutRequeue_when_claimedResultDeadlinePasses() {
        AutomaticRecoveryRequestTicket ticket = broker.enqueue(
                REQUEST_ID,
                AutomaticRecoveryProject.RHAOMI,
                AutomaticRecoveryTarget.BACKEND,
                AutomaticRecoveryAction.RESTART);
        broker.claimNext().orElseThrow();

        clock.advance(AutomaticRecoveryBroker.REQUEST_TTL
                .plus(AutomaticRecoveryBroker.RESULT_REPORTING_GRACE));

        assertThat(broker.claimNext()).isEmpty();
        assertThat(ticket.result().join().status())
                .isEqualTo(AutomaticRecoveryResultStatus.OUTCOME_UNKNOWN);
        assertThat(ticket.result().join().reasonCode())
                .isEqualTo(AutomaticRecoveryReasonCode.RESULT_UNAVAILABLE);
        assertThatThrownBy(() -> broker.complete(appliedResult(NOW, clock.instant())))
                .isInstanceOf(AutomaticRecoveryRequestGoneException.class);
        assertThat(broker.claimNext()).isEmpty();
    }

    @Test
    void should_rejectSecondActiveRequest_when_brokerCapacityIsReserved() {
        broker.enqueue(
                REQUEST_ID,
                AutomaticRecoveryProject.RHAOMI,
                AutomaticRecoveryTarget.BACKEND,
                AutomaticRecoveryAction.RESTART);

        assertThatThrownBy(() -> broker.enqueue(
                UUID.fromString("20000000-0000-4000-8000-000000000119"),
                AutomaticRecoveryProject.RHAOMI,
                AutomaticRecoveryTarget.RHAOMI_WEB,
                AutomaticRecoveryAction.RESTART))
                .isInstanceOf(AutomaticRecoveryBrokerCapacityException.class);
    }

    private static AgentRecoveryResultRequest appliedResult(
            Instant startedAt,
            Instant finishedAt) {
        return new AgentRecoveryResultRequest(
                REQUEST_ID,
                AutomaticRecoveryResultStatus.APPLIED,
                AutomaticRecoveryReasonCode.RECOVERY_APPLIED,
                startedAt,
                finishedAt,
                AutomaticRecoveryHealth.DOWN,
                AutomaticRecoveryHealth.UP,
                1);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
