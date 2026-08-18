package dev.homeops.agent.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import dev.homeops.agent.AgentSnapshotService;
import dev.homeops.agent.logs.api.AgentLogResultRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContainerLogBrokerTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Mock private AgentSnapshotService snapshotService;
    private MutableClock clock;
    private AtomicInteger sequence;
    private ContainerLogBroker broker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        sequence = new AtomicInteger();
        when(snapshotService.authorizeContainerLogs(anyString()))
                .thenAnswer(invocation -> new ContainerLogEligibility(
                        invocation.getArgument(0)));
        broker = new ContainerLogBroker(
                snapshotService,
                new ContainerLogRedactor(),
                clock,
                this::nextIdentifier,
                Duration.ZERO);
    }

    @Test
    void should_boundTotalAndPerContainerRequests() {
        broker.request("000000000001", 50);

        assertThatThrownBy(() -> broker.request("000000000001", 100))
                .isInstanceOf(ContainerLogRequestConflictException.class);

        broker.request("000000000002", 50);
        broker.request("000000000003", 50);
        broker.request("000000000004", 50);
        assertThatThrownBy(() -> broker.request("000000000005", 50))
                .isInstanceOf(ContainerLogBrokerCapacityException.class);
        assertThat(broker.activeRequestCount()).isEqualTo(4);
    }

    @Test
    void should_allowOnlyOneClaimedAgentWorkAtATime() {
        ContainerLogRequestTicket ticket = broker.request("000000000001", 50);
        broker.request("000000000002", 50);

        ContainerLogWork first = broker.claimNext().orElseThrow();

        assertThat(ticket.expiresAt()).isEqualTo(NOW.plusSeconds(6));
        assertThat(first.expiresAt()).isEqualTo(NOW.plusSeconds(6));
        assertThat(broker.claimNext()).isEmpty();
        broker.complete(success(first.requestId(), List.of("safe")));
        assertThat(broker.claimNext()).get()
                .extracting(ContainerLogWork::containerId)
                .isEqualTo("000000000002");
    }

    @Test
    void should_dropPendingWork_when_snapshotLosesDisclosureAuthority() {
        when(snapshotService.authorizeContainerLogs("000000000001"))
                .thenReturn(new ContainerLogEligibility("000000000001"))
                .thenThrow(new ContainerLogCapabilityUnavailableException());
        ContainerLogRequestTicket ticket = broker.request("000000000001", 50);

        assertThat(broker.claimNext()).isEmpty();
        assertThat(broker.activeRequestCount()).isZero();
        assertThatThrownBy(() -> ticket.result().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ContainerLogCapabilityUnavailableException.class);
    }

    @Test
    void should_discardClaimedResult_when_snapshotBecomesStaleBeforeCompletion() {
        when(snapshotService.authorizeContainerLogs("000000000001"))
                .thenReturn(new ContainerLogEligibility("000000000001"))
                .thenReturn(new ContainerLogEligibility("000000000001"))
                .thenThrow(new ContainerLogCapabilityUnavailableException());
        ContainerLogRequestTicket ticket = broker.request("000000000001", 50);
        ContainerLogWork work = broker.claimNext().orElseThrow();

        assertThatThrownBy(() -> broker.complete(success(
                work.requestId(),
                List.of("must not be disclosed"))))
                .isInstanceOf(ContainerLogRequestGoneException.class);
        assertThatThrownBy(() -> ticket.result().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ContainerLogCapabilityUnavailableException.class);
        assertThat(broker.activeRequestCount()).isZero();
    }

    @Test
    void should_expireAtSixSecondsAndRejectLateResult() {
        ContainerLogRequestTicket ticket = broker.request("000000000001", 50);
        ContainerLogWork work = broker.claimNext().orElseThrow();

        clock.advance(Duration.ofSeconds(6));
        broker.cleanupExpired();

        assertThat(broker.activeRequestCount()).isZero();
        assertThat(broker.tombstoneCount()).isEqualTo(1);
        assertThatThrownBy(() -> ticket.result().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ContainerLogRequestExpiredException.class);
        assertThatThrownBy(() -> broker.complete(success(work.requestId(), List.of("late"))))
                .isInstanceOf(ContainerLogRequestGoneException.class);
    }

    @Test
    void should_removePendingRequestAndPayloadReference_when_publicWaiterCancels() {
        ContainerLogRequestTicket ticket = broker.request("000000000001", 50);

        broker.cancel(ticket);

        assertThat(broker.activeRequestCount()).isZero();
        assertThat(broker.claimNext()).isEmpty();
        assertThatThrownBy(() -> ticket.result().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ContainerLogRequestCancelledException.class);
        assertThat(broker.tombstoneCount()).isEqualTo(1);
    }

    @Test
    void should_rejectLateAgentResult_when_claimedPublicWaiterCancels() {
        ContainerLogRequestTicket ticket = broker.request("000000000001", 50);
        ContainerLogWork work = broker.claimNext().orElseThrow();

        broker.cancel(ticket);

        assertThat(broker.activeRequestCount()).isZero();
        assertThatThrownBy(() -> broker.complete(success(
                work.requestId(), List.of("late payload"))))
                .isInstanceOf(ContainerLogRequestGoneException.class);
    }

    @Test
    void should_leaveCompletedResultIntact_when_cancelRacesAfterCompletion() {
        ContainerLogRequestTicket ticket = broker.request("000000000001", 50);
        ContainerLogWork work = broker.claimNext().orElseThrow();
        broker.complete(success(work.requestId(), List.of("safe")));

        broker.cancel(ticket);

        assertThat(ticket.result().toCompletableFuture().join().lines())
                .extracting(ContainerLogLine::message)
                .containsExactly("safe");
        assertThat(broker.tombstoneCount()).isEqualTo(1);
    }

    @Test
    void should_acceptDuplicateCompletedResultWithoutRetainingPayload() {
        broker.request("000000000001", 50);
        ContainerLogWork work = broker.claimNext().orElseThrow();
        AgentLogResultRequest result = success(work.requestId(), List.of("safe"));

        broker.complete(result);
        broker.complete(result);

        assertThat(broker.activeRequestCount()).isZero();
        assertThat(broker.tombstoneCount()).isEqualTo(1);
    }

    @Test
    void should_forgetCompletedTombstoneAfterThirtySeconds() {
        broker.request("000000000001", 50);
        ContainerLogWork work = broker.claimNext().orElseThrow();
        AgentLogResultRequest result = success(work.requestId(), List.of("safe"));
        broker.complete(result);

        clock.advance(Duration.ofSeconds(30));
        broker.cleanupExpired();

        assertThat(broker.tombstoneCount()).isZero();
        assertThatThrownBy(() -> broker.complete(result))
                .isInstanceOf(ContainerLogRequestGoneException.class);
    }

    @Test
    void should_redactAgainBeforeCompletingBackendResult() {
        ContainerLogRequestTicket ticket = broker.request("000000000001", 50);
        ContainerLogWork work = broker.claimNext().orElseThrow();

        broker.complete(success(work.requestId(), List.of(
                "password=synthetic-password",
                "Bearer synthetic-bearer")));

        ContainerLogResult result = ticket.result().toCompletableFuture().join();
        assertThat(result.lines()).extracting(ContainerLogLine::message)
                .containsExactly("password=[REDACTED]", "Bearer [REDACTED]")
                .allMatch(message -> !message.contains("synthetic"));
    }

    @Test
    void should_rejectFailurePayloadWithLinesAndOversizedMessageBytes() {
        broker.request("000000000001", 50);
        ContainerLogWork first = broker.claimNext().orElseThrow();
        AgentLogResultRequest failureWithLine = new AgentLogResultRequest(
                first.requestId(),
                ContainerLogResultStatus.UNAVAILABLE,
                NOW,
                List.of(line("raw")),
                false,
                false);

        assertThatThrownBy(() -> broker.complete(failureWithLine))
                .isInstanceOf(ContainerLogResultRejectedException.class);

        String oversized = "x".repeat(ContainerLogBroker.MAXIMUM_MESSAGE_BYTES + 1);
        assertThatThrownBy(() -> broker.complete(success(
                first.requestId(),
                List.of(oversized))))
                .isInstanceOf(ContainerLogResultRejectedException.class);
    }

    @Test
    void should_rejectMoreLinesThanRequestedTail() {
        broker.request("000000000001", 50);
        ContainerLogWork work = broker.claimNext().orElseThrow();
        List<String> messages = java.util.stream.IntStream.range(0, 51)
                .mapToObj(index -> "line-" + index)
                .toList();

        assertThatThrownBy(() -> broker.complete(success(work.requestId(), messages)))
                .isInstanceOf(ContainerLogResultRejectedException.class);
    }

    @Test
    void should_preserveAgentOrBackendRedactionMetadata() {
        ContainerLogRequestTicket backendTicket = broker.request("000000000001", 50);
        ContainerLogWork backendWork = broker.claimNext().orElseThrow();
        broker.complete(success(
                backendWork.requestId(),
                List.of("token=synthetic-token"),
                false,
                NOW));
        ContainerLogResult backendResult = backendTicket.result().toCompletableFuture().join();
        assertThat(backendResult.redactionApplied()).isTrue();
        assertThat(backendResult.collectedAt()).isEqualTo(NOW);

        ContainerLogRequestTicket agentTicket = broker.request("000000000002", 50);
        ContainerLogWork agentWork = broker.claimNext().orElseThrow();
        broker.complete(success(
                agentWork.requestId(),
                List.of("already safe"),
                true,
                NOW));
        assertThat(agentTicket.result().toCompletableFuture().join().redactionApplied())
                .isTrue();

        ContainerLogRequestTicket plainTicket = broker.request("000000000003", 50);
        ContainerLogWork plainWork = broker.claimNext().orElseThrow();
        broker.complete(success(plainWork.requestId(), List.of("plain")));
        assertThat(plainTicket.result().toCompletableFuture().join().redactionApplied())
                .isFalse();
    }

    @Test
    void should_rejectCollectedTimestampOutsideBoundedRequestLifecycle() {
        broker.request("000000000001", 50);
        ContainerLogWork work = broker.claimNext().orElseThrow();

        assertThatThrownBy(() -> broker.complete(success(
                work.requestId(),
                List.of("safe"),
                false,
                NOW.plus(ContainerLogBroker.RESULT_TIMESTAMP_SKEW).plusMillis(1))))
                .isInstanceOf(ContainerLogResultRejectedException.class);
        assertThatThrownBy(() -> broker.complete(success(
                work.requestId(),
                List.of("safe"),
                false,
                NOW.minus(ContainerLogBroker.RESULT_TIMESTAMP_SKEW).minusMillis(1))))
                .isInstanceOf(ContainerLogResultRejectedException.class);
    }

    @Test
    void should_boundMetadataOnlyTombstones() {
        for (int index = 0; index < 20; index++) {
            String containerId = "%012x".formatted(index + 1);
            broker.request(containerId, 50);
            ContainerLogWork work = broker.claimNext().orElseThrow();
            broker.complete(success(work.requestId(), List.of("safe")));
        }

        assertThat(broker.tombstoneCount())
                .isEqualTo(ContainerLogBroker.MAXIMUM_TOMBSTONES);
    }

    private UUID nextIdentifier() {
        return UUID.fromString("10000000-0000-4000-8000-%012d".formatted(
                sequence.incrementAndGet()));
    }

    private static AgentLogResultRequest success(
            UUID requestId,
            List<String> messages) {
        return success(requestId, messages, false, NOW);
    }

    private static AgentLogResultRequest success(
            UUID requestId,
            List<String> messages,
            boolean redactionApplied,
            Instant collectedAt) {
        List<AgentLogResultRequest.Line> lines = new ArrayList<>();
        for (String message : messages) {
            lines.add(line(message));
        }
        return new AgentLogResultRequest(
                requestId,
                ContainerLogResultStatus.SUCCESS,
                collectedAt,
                lines,
                false,
                redactionApplied);
    }

    private static AgentLogResultRequest.Line line(String message) {
        return new AgentLogResultRequest.Line(
                NOW,
                ContainerLogStream.STDOUT,
                message);
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
