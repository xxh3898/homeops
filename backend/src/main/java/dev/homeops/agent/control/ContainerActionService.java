package dev.homeops.agent.control;

import dev.homeops.agent.ContainerControlAuthority.DecisionCode;
import dev.homeops.system.ContainerIdentifier;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class ContainerActionService {
    static final int MAXIMUM_PRINCIPAL_LENGTH = 256;
    static final String CONTROL_BUSY = "CONTROL_BUSY";
    static final String CONTROL_RESULT_UNAVAILABLE = "CONTROL_RESULT_UNAVAILABLE";

    private final ContainerActionAuditTransactions audit;
    private final ContainerActionRateLimiter rateLimiter;
    private final ContainerControlBroker broker;
    private final Executor auditExecutor;
    private final Clock clock;

    @Autowired
    public ContainerActionService(
            ContainerActionAuditTransactions audit,
            ContainerActionRateLimiter rateLimiter,
            ContainerControlBroker broker,
            @Qualifier("containerControlAuditExecutor") Executor auditExecutor) {
        this(audit, rateLimiter, broker, auditExecutor, Clock.systemUTC());
    }

    ContainerActionService(
            ContainerActionAuditTransactions audit,
            ContainerActionRateLimiter rateLimiter,
            ContainerControlBroker broker,
            Executor auditExecutor,
            Clock clock) {
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.broker = broker;
        this.auditExecutor = auditExecutor;
        this.clock = clock;
    }

    public Submission submit(
            String rawContainerId,
            ContainerControlOperation operation,
            String confirmation,
            ContainerActionIdempotencyKey idempotencyKey,
            String principal) {
        String containerId = ContainerIdentifier.parse(rawContainerId).value();
        requirePrincipal(principal);
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (!expectedConfirmation(operation, containerId).equals(confirmation)) {
            throw ContainerActionException.confirmationMismatch();
        }

        ContainerActionAuditRecord existing = findExisting(idempotencyKey.value()).orElse(null);
        if (existing != null) {
            return replay(existing, principal, containerId, operation);
        }

        if (!rateLimiter.tryAcquire(principal, idempotencyKey.value())) {
            throw ContainerActionException.rateLimited();
        }

        ContainerActionReservation reservation;
        try {
            reservation = audit.reserve(
                    idempotencyKey.value(),
                    principal,
                    containerId,
                    operation);
        } catch (DataAccessException exception) {
            throw ContainerActionException.unavailable();
        }
        ContainerActionAuditRecord record = reservation.record();
        if (!record.matches(principal, containerId, operation)) {
            throw ContainerActionException.conflict();
        }
        if (!reservation.created()) {
            return new Submission(record, false);
        }

        ContainerControlRequestTicket ticket;
        try {
            ticket = broker.enqueue(containerId, operation);
        } catch (ContainerControlDeniedException exception) {
            terminalize(
                    record.operationId(),
                    ContainerActionStatus.DENIED,
                    authorityReason(exception.decisionCode()));
            throw ContainerActionException.denied();
        } catch (ContainerControlBrokerCapacityException
                | ContainerControlRequestConflictException exception) {
            terminalize(record.operationId(), ContainerActionStatus.DENIED, CONTROL_BUSY);
            throw ContainerActionException.busy();
        }

        ticket.result().whenCompleteAsync(
                (result, failure) -> projectResult(record.operationId(), result, failure),
                auditExecutor);
        return new Submission(record, true);
    }

    private Optional<ContainerActionAuditRecord> findExisting(String idempotencyKey) {
        try {
            return audit.findByIdempotencyKey(idempotencyKey);
        } catch (DataAccessException exception) {
            throw ContainerActionException.unavailable();
        }
    }

    private static Submission replay(
            ContainerActionAuditRecord record,
            String principal,
            String containerId,
            ContainerControlOperation operation) {
        if (!record.matches(principal, containerId, operation)) {
            throw ContainerActionException.conflict();
        }
        return new Submission(record, false);
    }

    public ContainerActionAuditRecord find(UUID operationId) {
        ContainerActionAuditRecord record = audit.find(operationId)
                .filter(ContainerActionAuditRecord::hasPublicIdentifier)
                .orElseThrow(ContainerActionException::notFound);
        boolean validShape = record.status() == ContainerActionStatus.REQUESTED
                ? record.reasonCode() == null && record.completedAt() == null
                : record.reasonCode() != null
                        && !record.reasonCode().isBlank()
                        && record.completedAt() != null;
        if (!validShape) {
            throw ContainerActionException.notFound();
        }
        return record;
    }

    private void projectResult(
            UUID operationId,
            ContainerControlResult result,
            Throwable failure) {
        if (failure == null && result != null) {
            audit.complete(
                    operationId,
                    ContainerActionStatus.valueOf(result.status().name()),
                    result.reasonCode().name(),
                    result.finishedAt());
            return;
        }
        Throwable cause = unwrap(failure);
        if (cause instanceof ContainerControlDeniedException denied) {
            terminalize(
                    operationId,
                    ContainerActionStatus.DENIED,
                    authorityReason(denied.decisionCode()));
            return;
        }
        terminalize(
                operationId,
                ContainerActionStatus.FAILED,
                CONTROL_RESULT_UNAVAILABLE);
    }

    private void terminalize(
            UUID operationId,
            ContainerActionStatus status,
            String reasonCode) {
        audit.complete(operationId, status, reasonCode, clock.instant());
    }

    private static String expectedConfirmation(
            ContainerControlOperation operation,
            String containerId) {
        return operation.name() + ":" + containerId;
    }

    private static void requirePrincipal(String principal) {
        if (principal == null
                || principal.isBlank()
                || principal.length() > MAXIMUM_PRINCIPAL_LENGTH
                || principal.indexOf('\0') >= 0) {
            throw ContainerActionException.unavailable();
        }
    }

    private static String authorityReason(DecisionCode decisionCode) {
        return decisionCode == null ? CONTROL_RESULT_UNAVAILABLE : decisionCode.name();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public record Submission(ContainerActionAuditRecord record, boolean created) {
    }
}
