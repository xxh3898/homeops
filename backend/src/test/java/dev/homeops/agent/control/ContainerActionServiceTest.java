package dev.homeops.agent.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.homeops.agent.ContainerControlAuthority.DecisionCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class ContainerActionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-20T15:00:00Z");
    private static final UUID OPERATION_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final String IDEMPOTENCY_KEY = "20000000-0000-4000-8000-000000000001";
    private static final String CONTAINER_ID = "0123456789ab";
    private static final String PRINCIPAL = "admin@example.test";

    @Mock private ContainerActionAuditTransactions audit;
    @Mock private ContainerActionRateLimiter rateLimiter;
    @Mock private ContainerControlBroker broker;
    private ContainerActionService service;

    @BeforeEach
    void setUp() {
        service = new ContainerActionService(
                audit,
                rateLimiter,
                broker,
                Runnable::run,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void should_returnExistingOperationWithoutRateOrDispatch_when_requestIsExactReplay() {
        ContainerActionAuditRecord existing = terminal(ContainerActionStatus.APPLIED, "APPLIED");
        when(audit.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

        ContainerActionService.Submission result = submit();

        assertThat(result.created()).isFalse();
        assertThat(result.record()).isEqualTo(existing);
        verify(audit, never()).reserve(
                anyString(), anyString(), anyString(), anyOperation());
        verifyNoInteractions(rateLimiter, broker);
    }

    @Test
    void should_projectExactBrokerResult_when_newRequestCompletes() {
        CompletableFuture<ContainerControlResult> result = new CompletableFuture<>();
        stubNewReservation(requested());
        when(broker.enqueue(CONTAINER_ID, ContainerControlOperation.START))
                .thenReturn(new ContainerControlRequestTicket(
                        UUID.randomUUID(), NOW.plusSeconds(15), result));

        ContainerActionService.Submission submission = submit();
        result.complete(new ContainerControlResult(
                ContainerControlResultStatus.NOOP,
                ContainerControlReasonCode.ALREADY_RUNNING,
                NOW.plusSeconds(1)));

        assertThat(submission.created()).isTrue();
        verify(audit).complete(
                OPERATION_ID,
                ContainerActionStatus.NOOP,
                "ALREADY_RUNNING",
                NOW.plusSeconds(1));
    }

    @ParameterizedTest
    @MethodSource("terminalResults")
    void should_projectEveryBrokerTerminalStatusOneToOne_when_resultCompletes(
            ContainerControlResultStatus resultStatus,
            ContainerControlReasonCode reasonCode) {
        CompletableFuture<ContainerControlResult> result = new CompletableFuture<>();
        stubNewReservation(requested());
        when(broker.enqueue(CONTAINER_ID, ContainerControlOperation.START))
                .thenReturn(new ContainerControlRequestTicket(
                        UUID.randomUUID(), NOW.plusSeconds(15), result));

        submit();
        result.complete(new ContainerControlResult(
                resultStatus,
                reasonCode,
                NOW.plusSeconds(1)));

        verify(audit).complete(
                OPERATION_ID,
                ContainerActionStatus.valueOf(resultStatus.name()),
                reasonCode.name(),
                NOW.plusSeconds(1));
    }

    @Test
    void should_scheduleAuditProjectionAwayFromBrokerCompletionThread() {
        CompletableFuture<ContainerControlResult> result = new CompletableFuture<>();
        List<Runnable> tasks = new ArrayList<>();
        service = new ContainerActionService(
                audit,
                rateLimiter,
                broker,
                tasks::add,
                Clock.fixed(NOW, ZoneOffset.UTC));
        stubNewReservation(requested());
        when(broker.enqueue(CONTAINER_ID, ContainerControlOperation.START))
                .thenReturn(new ContainerControlRequestTicket(
                        UUID.randomUUID(), NOW.plusSeconds(15), result));

        submit();
        result.complete(new ContainerControlResult(
                ContainerControlResultStatus.APPLIED,
                ContainerControlReasonCode.APPLIED,
                NOW.plusSeconds(1)));

        verify(audit, never()).complete(anyUuid(), anyStatus(), anyString(), anyInstant());
        assertThat(tasks).hasSize(1);
        tasks.getFirst().run();
        verify(audit).complete(
                OPERATION_ID,
                ContainerActionStatus.APPLIED,
                "APPLIED",
                NOW.plusSeconds(1));
    }

    @Test
    void should_rejectWithoutDurableReservationOrDispatch_when_rateLimitIsExceeded() {
        when(audit.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(rateLimiter.tryAcquire(PRINCIPAL, IDEMPOTENCY_KEY)).thenReturn(false);

        assertThatThrownBy(this::submit)
                .isInstanceOf(ContainerActionException.class)
                .extracting("status")
                .isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
        verify(audit, never()).reserve(
                anyString(), anyString(), anyString(), anyOperation());
        verify(audit, never()).complete(anyUuid(), anyStatus(), anyString(), anyInstant());
        verifyNoInteractions(broker);
    }

    @Test
    void should_terminalizeAuthorityReasonWithoutRawError_when_brokerDenies() {
        stubNewReservation(requested());
        when(broker.enqueue(CONTAINER_ID, ContainerControlOperation.START))
                .thenThrow(new ContainerControlDeniedException(DecisionCode.STALE_SNAPSHOT));

        assertThatThrownBy(this::submit)
                .isInstanceOf(ContainerActionException.class)
                .extracting("status")
                .isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT);
        verify(audit).complete(
                OPERATION_ID,
                ContainerActionStatus.DENIED,
                "STALE_SNAPSHOT",
                NOW);
    }

    @Test
    void should_terminalizeClaimTimeAuthorityDenial_when_brokerFutureFails() {
        CompletableFuture<ContainerControlResult> result = new CompletableFuture<>();
        stubNewReservation(requested());
        when(broker.enqueue(CONTAINER_ID, ContainerControlOperation.START))
                .thenReturn(new ContainerControlRequestTicket(
                        UUID.randomUUID(), NOW.plusSeconds(15), result));

        submit();
        result.completeExceptionally(
                new ContainerControlDeniedException(DecisionCode.PROJECT_NOT_ALLOWED));

        verify(audit).complete(
                OPERATION_ID,
                ContainerActionStatus.DENIED,
                "PROJECT_NOT_ALLOWED",
                NOW);
    }

    @Test
    void should_terminalizeBusyWithoutRetry_when_brokerHasActiveWork() {
        stubNewReservation(requested());
        when(broker.enqueue(CONTAINER_ID, ContainerControlOperation.START))
                .thenThrow(new ContainerControlBrokerCapacityException());

        assertThatThrownBy(this::submit)
                .isInstanceOf(ContainerActionException.class)
                .extracting("status")
                .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        verify(audit).complete(
                OPERATION_ID,
                ContainerActionStatus.DENIED,
                ContainerActionService.CONTROL_BUSY,
                NOW);
    }

    @ParameterizedTest
    @MethodSource("conflictingPayloads")
    void should_rejectExistingIdempotencyConflictWithoutRateReservationOrDispatch(
            String principal,
            String containerId,
            ContainerControlOperation operation) {
        when(audit.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(requested()));

        assertThatThrownBy(() -> service.submit(
                containerId,
                operation,
                operation.name() + ":" + containerId,
                ContainerActionIdempotencyKey.parse(IDEMPOTENCY_KEY),
                principal))
                .isInstanceOf(ContainerActionException.class)
                .extracting("status")
                .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        verify(audit, never()).reserve(
                anyString(), anyString(), anyString(), anyOperation());
        verifyNoInteractions(rateLimiter, broker);
    }

    @Test
    void should_keepKeyReservationAndNeverDispatchFirstAttempt_when_auditReservationFails() {
        ContainerActionRateLimiter keyAwareLimiter = new ContainerActionRateLimiter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        service = new ContainerActionService(
                audit,
                keyAwareLimiter,
                broker,
                Runnable::run,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(audit.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(audit.reserve(
                IDEMPOTENCY_KEY,
                PRINCIPAL,
                CONTAINER_ID,
                ContainerControlOperation.START))
                .thenThrow(new DataAccessResourceFailureException("private-db-detail"))
                .thenReturn(new ContainerActionReservation(requested(), true));
        when(broker.enqueue(CONTAINER_ID, ContainerControlOperation.START))
                .thenReturn(new ContainerControlRequestTicket(
                        UUID.randomUUID(), NOW.plusSeconds(15), new CompletableFuture<>()));

        assertThatThrownBy(this::submit)
                .isInstanceOf(ContainerActionException.class)
                .hasMessageNotContaining("private-db-detail")
                .extracting("status")
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(broker);

        assertThat(submit().created()).isTrue();
        for (int key = 1; key < ContainerActionRateLimiter.MAXIMUM_REQUESTS; key++) {
            assertThat(keyAwareLimiter.tryAcquire(PRINCIPAL, "other-key-" + key)).isTrue();
        }
        assertThat(keyAwareLimiter.tryAcquire(PRINCIPAL, "overflow-key")).isFalse();
        verify(broker).enqueue(CONTAINER_ID, ContainerControlOperation.START);
    }

    @Test
    void should_failClosedWithoutRateOrDispatch_when_auditLookupFails() {
        when(audit.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenThrow(new DataAccessResourceFailureException("private-db-detail"));

        assertThatThrownBy(this::submit)
                .isInstanceOf(ContainerActionException.class)
                .hasMessageNotContaining("private-db-detail")
                .extracting("status")
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(rateLimiter, broker);
    }

    @Test
    void should_rejectConfirmationBeforeAudit_when_confirmationDoesNotMatch() {
        assertThatThrownBy(() -> service.submit(
                CONTAINER_ID,
                ContainerControlOperation.START,
                "start:" + CONTAINER_ID,
                ContainerActionIdempotencyKey.parse(IDEMPOTENCY_KEY),
                PRINCIPAL))
                .isInstanceOf(ContainerActionException.class);

        verifyNoInteractions(audit, rateLimiter, broker);
    }

    @Test
    void should_hideLegacyNonPublicIdentifier_when_operationIsRead() {
        when(audit.find(OPERATION_ID)).thenReturn(Optional.of(new ContainerActionAuditRecord(
                OPERATION_ID,
                "legacy-key",
                PRINCIPAL,
                "legacy-container-id",
                ContainerControlOperation.START,
                ContainerActionStatus.APPLIED,
                "LEGACY_SUCCESS",
                NOW,
                NOW)));

        assertThatThrownBy(() -> service.find(OPERATION_ID))
                .isInstanceOf(ContainerActionException.class)
                .extracting("status")
                .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        verify(audit, never()).complete(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private ContainerActionService.Submission submit() {
        return service.submit(
                CONTAINER_ID,
                ContainerControlOperation.START,
                "START:" + CONTAINER_ID,
                ContainerActionIdempotencyKey.parse(IDEMPOTENCY_KEY),
                PRINCIPAL);
    }

    private static ContainerActionAuditRecord requested() {
        return new ContainerActionAuditRecord(
                OPERATION_ID,
                IDEMPOTENCY_KEY,
                PRINCIPAL,
                CONTAINER_ID,
                ContainerControlOperation.START,
                ContainerActionStatus.REQUESTED,
                null,
                NOW,
                null);
    }

    private static ContainerActionAuditRecord terminal(
            ContainerActionStatus status,
            String reason) {
        return new ContainerActionAuditRecord(
                OPERATION_ID,
                IDEMPOTENCY_KEY,
                PRINCIPAL,
                CONTAINER_ID,
                ContainerControlOperation.START,
                status,
                reason,
                NOW,
                NOW.plusSeconds(1));
    }

    private static Stream<Arguments> terminalResults() {
        return Stream.of(
                Arguments.of(ContainerControlResultStatus.APPLIED, ContainerControlReasonCode.APPLIED),
                Arguments.of(ContainerControlResultStatus.NOOP, ContainerControlReasonCode.ALREADY_RUNNING),
                Arguments.of(ContainerControlResultStatus.DENIED, ContainerControlReasonCode.NOT_MANAGED),
                Arguments.of(ContainerControlResultStatus.FAILED, ContainerControlReasonCode.DOCKER_REJECTED),
                Arguments.of(
                        ContainerControlResultStatus.OUTCOME_UNKNOWN,
                        ContainerControlReasonCode.DOCKER_OUTCOME_UNKNOWN),
                Arguments.of(ContainerControlResultStatus.EXPIRED, ContainerControlReasonCode.WORK_EXPIRED));
    }

    private static Stream<Arguments> conflictingPayloads() {
        return Stream.of(
                Arguments.of(
                        "different@example.test",
                        CONTAINER_ID,
                        ContainerControlOperation.START),
                Arguments.of(
                        PRINCIPAL,
                        "abcdef012345",
                        ContainerControlOperation.START),
                Arguments.of(
                        PRINCIPAL,
                        CONTAINER_ID,
                        ContainerControlOperation.STOP));
    }

    private void stubNewReservation(ContainerActionAuditRecord record) {
        when(audit.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(rateLimiter.tryAcquire(PRINCIPAL, IDEMPOTENCY_KEY)).thenReturn(true);
        when(audit.reserve(
                IDEMPOTENCY_KEY,
                PRINCIPAL,
                CONTAINER_ID,
                ContainerControlOperation.START))
                .thenReturn(new ContainerActionReservation(record, true));
    }

    private static UUID anyUuid() {
        return org.mockito.ArgumentMatchers.any(UUID.class);
    }

    private static ContainerActionStatus anyStatus() {
        return org.mockito.ArgumentMatchers.any(ContainerActionStatus.class);
    }

    private static ContainerControlOperation anyOperation() {
        return org.mockito.ArgumentMatchers.any(ContainerControlOperation.class);
    }

    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }

    private static Instant anyInstant() {
        return org.mockito.ArgumentMatchers.any(Instant.class);
    }
}
