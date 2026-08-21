package dev.homeops.agent.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import dev.homeops.agent.ContainerControlAuthority;
import dev.homeops.agent.ContainerControlAuthority.Decision;
import dev.homeops.agent.ContainerControlAuthority.DecisionCode;
import dev.homeops.agent.ContainerControlAuthority.Target;
import dev.homeops.agent.control.api.AgentControlResultRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContainerControlBrokerTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String CONTAINER_ID = "0123456789ab";

    @Mock private ContainerControlAuthority authority;
    private MutableClock clock;
    private AtomicInteger sequence;
    private ContainerControlBroker broker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        sequence = new AtomicInteger();
        when(authority.evaluate(anyString()))
                .thenAnswer(invocation -> eligible(invocation.getArgument(0), "example"));
        broker = new ContainerControlBroker(
                authority,
                clock,
                this::nextIdentifier,
                Duration.ZERO);
    }

    @Test
    void should_enqueueOnlyEligibleBoundedTarget_when_authorityAllowsCandidate() {
        ContainerControlRequestTicket ticket = broker.enqueue(
                CONTAINER_ID,
                ContainerControlOperation.RESTART);

        ContainerControlWork work = broker.claimNext().orElseThrow();

        assertThat(work.requestId()).isEqualTo(ticket.requestId());
        assertThat(work.containerId()).isEqualTo(CONTAINER_ID);
        assertThat(work.composeProject()).isEqualTo("example");
        assertThat(work.operation()).isEqualTo(ContainerControlOperation.RESTART);
        assertThat(work.expiresAt()).isEqualTo(NOW.plusSeconds(15));
        assertThat(work.toString()).doesNotContain("composeProject=example");
    }

    @Test
    void should_rejectEnqueue_when_authorityDeniesCandidate() {
        when(authority.evaluate(CONTAINER_ID))
                .thenReturn(denied(DecisionCode.STALE_SNAPSHOT));

        assertThatThrownBy(() -> broker.enqueue(
                CONTAINER_ID,
                ContainerControlOperation.START))
                .isInstanceOf(ContainerControlDeniedException.class)
                .extracting("decisionCode")
                .isEqualTo(DecisionCode.STALE_SNAPSHOT);
        assertThat(broker.activeRequestCount()).isZero();
    }

    @Test
    void should_boundGlobalAndDuplicateActiveWork() {
        broker.enqueue(CONTAINER_ID, ContainerControlOperation.START);

        assertThatThrownBy(() -> broker.enqueue(
                CONTAINER_ID,
                ContainerControlOperation.STOP))
                .isInstanceOf(ContainerControlRequestConflictException.class);
        assertThatThrownBy(() -> broker.enqueue(
                "aaaaaaaaaaaa",
                ContainerControlOperation.STOP))
                .isInstanceOf(ContainerControlBrokerCapacityException.class);
        assertThat(broker.activeRequestCount()).isOne();
    }

    @Test
    void should_failClosedBeforeClaim_when_authorityOrProjectChanges() {
        when(authority.evaluate(CONTAINER_ID))
                .thenReturn(eligible(CONTAINER_ID, "example"))
                .thenReturn(eligible(CONTAINER_ID, "different"));
        ContainerControlRequestTicket ticket = broker.enqueue(
                CONTAINER_ID,
                ContainerControlOperation.START);

        assertThat(broker.claimNext()).isEmpty();
        assertThatThrownBy(() -> ticket.result().toCompletableFuture().join())
                .hasCauseInstanceOf(ContainerControlDeniedException.class);
        assertThat(broker.activeRequestCount()).isZero();
    }

    @Test
    void should_allowOnlyOneClaimWinner_underConcurrentPolls() throws Exception {
        broker.enqueue(CONTAINER_ID, ContainerControlOperation.START);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> polls = List.of(
                    () -> broker.claimNext().isPresent(),
                    () -> broker.claimNext().isPresent());

            long winners = 0;
            for (var future : executor.invokeAll(polls)) {
                if (future.get()) {
                    winners++;
                }
            }
            assertThat(winners).isOne();
        }
    }

    @Test
    void should_completeFirstResultAndAcceptDuplicateDeliveryIdempotently() {
        ContainerControlRequestTicket ticket = broker.enqueue(
                CONTAINER_ID,
                ContainerControlOperation.START);
        ContainerControlWork work = broker.claimNext().orElseThrow();
        AgentControlResultRequest result = applied(work.requestId(), NOW.plusSeconds(1));

        broker.complete(result);
        broker.complete(result);

        assertThat(ticket.result().toCompletableFuture().join())
                .isEqualTo(new ContainerControlResult(
                        ContainerControlResultStatus.APPLIED,
                        ContainerControlReasonCode.APPLIED,
                        NOW.plusSeconds(1)));
        assertThat(broker.activeRequestCount()).isZero();
        assertThat(broker.tombstoneCount()).isOne();
    }

    @Test
    void should_rejectUnknownPendingLateAndInvalidResults() {
        ContainerControlRequestTicket ticket = broker.enqueue(
                CONTAINER_ID,
                ContainerControlOperation.START);
        assertThatThrownBy(() -> broker.complete(applied(
                ticket.requestId(),
                NOW.plusSeconds(1))))
                .isInstanceOf(ContainerControlRequestGoneException.class);

        ContainerControlWork work = broker.claimNext().orElseThrow();
        assertThatThrownBy(() -> broker.complete(new AgentControlResultRequest(
                work.requestId(),
                ContainerControlResultStatus.APPLIED,
                ContainerControlReasonCode.DOCKER_REJECTED,
                NOW.plusSeconds(1))))
                .isInstanceOf(ContainerControlResultRejectedException.class);
        assertThatThrownBy(() -> broker.complete(applied(
                work.requestId(),
                NOW.minusSeconds(2))))
                .isInstanceOf(ContainerControlResultRejectedException.class);

        broker.complete(applied(work.requestId(), NOW.plusSeconds(1)));
        assertThatThrownBy(() -> broker.complete(applied(
                UUID.fromString("20000000-0000-4000-8000-000000000001"),
                NOW.plusSeconds(1))))
                .isInstanceOf(ContainerControlRequestGoneException.class);
    }

    @Test
    void should_keepClaimedWorkActiveAtOperationDeadline() {
        ContainerControlRequestTicket ticket = broker.enqueue(
                CONTAINER_ID,
                ContainerControlOperation.STOP);
        broker.claimNext().orElseThrow();

        clock.advance(Duration.ofSeconds(15));
        broker.cleanupExpired();

        assertThat(ticket.result().toCompletableFuture().isDone()).isFalse();
        assertThat(broker.activeRequestCount()).isOne();
        assertThat(broker.claimNext()).isEmpty();
    }

    @Test
    void should_expirePendingWorkAtOperationDeadline() {
        ContainerControlRequestTicket ticket = broker.enqueue(
                CONTAINER_ID,
                ContainerControlOperation.STOP);

        clock.advance(ContainerControlBroker.REQUEST_TTL);
        broker.cleanupExpired();

        assertThat(ticket.result().toCompletableFuture().join())
                .isEqualTo(new ContainerControlResult(
                        ContainerControlResultStatus.EXPIRED,
                        ContainerControlReasonCode.WORK_EXPIRED,
                        NOW.plus(ContainerControlBroker.REQUEST_TTL)));
        assertThat(broker.activeRequestCount()).isZero();
        assertThat(broker.claimNext()).isEmpty();
    }

    @Test
    void should_acceptAgentResultDuringGraceAfterOperationDeadline() {
        ContainerControlRequestTicket ticket = broker.enqueue(
                CONTAINER_ID,
                ContainerControlOperation.RESTART);
        ContainerControlWork work = broker.claimNext().orElseThrow();

        clock.advance(ContainerControlBroker.REQUEST_TTL.plusSeconds(5));
        AgentControlResultRequest outcomeUnknown = new AgentControlResultRequest(
                work.requestId(),
                ContainerControlResultStatus.OUTCOME_UNKNOWN,
                ContainerControlReasonCode.DOCKER_OUTCOME_UNKNOWN,
                NOW.plus(ContainerControlBroker.REQUEST_TTL));
        broker.complete(outcomeUnknown);

        assertThat(ticket.result().toCompletableFuture().join())
                .isEqualTo(new ContainerControlResult(
                        ContainerControlResultStatus.OUTCOME_UNKNOWN,
                        ContainerControlReasonCode.DOCKER_OUTCOME_UNKNOWN,
                        NOW.plus(ContainerControlBroker.REQUEST_TTL)));
        broker.complete(outcomeUnknown);
        assertThat(broker.activeRequestCount()).isZero();
        assertThat(broker.claimNext()).isEmpty();
    }

    @Test
    void should_acceptAppliedResultDuringGraceAfterOperationDeadline() {
        ContainerControlRequestTicket ticket = broker.enqueue(
                CONTAINER_ID,
                ContainerControlOperation.START);
        ContainerControlWork work = broker.claimNext().orElseThrow();

        clock.advance(ContainerControlBroker.REQUEST_TTL.plusSeconds(5));
        broker.complete(applied(
                work.requestId(),
                NOW.plus(ContainerControlBroker.REQUEST_TTL)));

        assertThat(ticket.result().toCompletableFuture().join())
                .isEqualTo(new ContainerControlResult(
                        ContainerControlResultStatus.APPLIED,
                        ContainerControlReasonCode.APPLIED,
                        NOW.plus(ContainerControlBroker.REQUEST_TTL)));
        assertThat(broker.activeRequestCount()).isZero();
    }

    @Test
    void should_terminalizeMissingClaimedResultAsUnknownAtGraceDeadline() {
        ContainerControlRequestTicket ticket = broker.enqueue(
                CONTAINER_ID,
                ContainerControlOperation.START);
        ContainerControlWork work = broker.claimNext().orElseThrow();

        clock.advance(ContainerControlBroker.REQUEST_TTL
                .plus(ContainerControlBroker.RESULT_REPORTING_GRACE));
        broker.cleanupExpired();

        assertThat(ticket.result().toCompletableFuture().join())
                .isEqualTo(new ContainerControlResult(
                        ContainerControlResultStatus.OUTCOME_UNKNOWN,
                        ContainerControlReasonCode.RESULT_UNAVAILABLE,
                        NOW.plus(ContainerControlBroker.REQUEST_TTL)));
        assertThat(broker.activeRequestCount()).isZero();
        assertThat(broker.claimNext()).isEmpty();
        assertThatThrownBy(() -> broker.complete(applied(
                work.requestId(),
                NOW.plusSeconds(14))))
                .isInstanceOf(ContainerControlRequestGoneException.class);
    }

    @Test
    void should_rejectAgentSuppliedMissingResultReasonAndLateExecutionTimestamp() {
        broker.enqueue(CONTAINER_ID, ContainerControlOperation.START);
        ContainerControlWork work = broker.claimNext().orElseThrow();
        clock.advance(ContainerControlBroker.REQUEST_TTL.plusSeconds(5));

        assertThatThrownBy(() -> broker.complete(new AgentControlResultRequest(
                work.requestId(),
                ContainerControlResultStatus.OUTCOME_UNKNOWN,
                ContainerControlReasonCode.RESULT_UNAVAILABLE,
                NOW.plusSeconds(14))))
                .isInstanceOf(ContainerControlResultRejectedException.class);
        assertThatThrownBy(() -> broker.complete(applied(
                work.requestId(),
                NOW.plus(ContainerControlBroker.REQUEST_TTL)
                        .plus(ContainerControlBroker.RESULT_TIMESTAMP_SKEW))))
                .isInstanceOf(ContainerControlResultRejectedException.class);
    }

    @Test
    void should_cancelPendingOrClaimedWorkAndRejectLateResult() {
        ContainerControlRequestTicket pending = broker.enqueue(
                CONTAINER_ID,
                ContainerControlOperation.START);
        broker.cancel(pending);
        assertThatThrownBy(() -> pending.result().toCompletableFuture().join())
                .hasCauseInstanceOf(ContainerControlRequestCancelledException.class);

        ContainerControlRequestTicket claimed = broker.enqueue(
                CONTAINER_ID,
                ContainerControlOperation.STOP);
        ContainerControlWork work = broker.claimNext().orElseThrow();
        broker.cancel(claimed);

        assertThatThrownBy(() -> broker.complete(applied(
                work.requestId(),
                NOW.plusSeconds(1))))
                .isInstanceOf(ContainerControlRequestGoneException.class);
        assertThat(broker.claimNext()).isEmpty();
    }

    @Test
    void should_boundMetadataOnlyTombstonesAndForgetThemAfterTtl() {
        List<UUID> completed = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            String identifier = "%012x".formatted(index + 1);
            broker.enqueue(identifier, ContainerControlOperation.START);
            ContainerControlWork work = broker.claimNext().orElseThrow();
            broker.complete(applied(work.requestId(), NOW));
            completed.add(work.requestId());
        }

        assertThat(broker.tombstoneCount())
                .isEqualTo(ContainerControlBroker.MAXIMUM_TOMBSTONES);
        clock.advance(Duration.ofSeconds(30));
        broker.cleanupExpired();
        assertThat(broker.tombstoneCount()).isZero();
        assertThatThrownBy(() -> broker.complete(applied(completed.getLast(), NOW)))
                .isInstanceOf(ContainerControlRequestGoneException.class);
    }

    private UUID nextIdentifier() {
        return UUID.fromString("10000000-0000-4000-8000-%012d".formatted(
                sequence.incrementAndGet()));
    }

    private static Decision eligible(String containerId, String project) {
        return new Decision(DecisionCode.ELIGIBLE, new Target(containerId, project));
    }

    private static Decision denied(DecisionCode code) {
        return new Decision(code, null);
    }

    private static AgentControlResultRequest applied(UUID requestId, Instant finishedAt) {
        return new AgentControlResultRequest(
                requestId,
                ContainerControlResultStatus.APPLIED,
                ContainerControlReasonCode.APPLIED,
                finishedAt);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
