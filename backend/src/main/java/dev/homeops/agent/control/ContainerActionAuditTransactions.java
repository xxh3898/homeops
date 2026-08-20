package dev.homeops.agent.control;

import dev.homeops.common.PostgresqlTimestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class ContainerActionAuditTransactions {
    static final Duration RECONCILIATION_SAFETY_MARGIN = Duration.ofSeconds(5);
    static final Duration STALE_REQUEST_AFTER = ContainerControlBroker.REQUEST_TTL
            .plus(ContainerControlBroker.RESULT_REPORTING_GRACE)
            .plus(RECONCILIATION_SAFETY_MARGIN);
    static final int RECONCILIATION_BATCH_SIZE = 32;

    private final ContainerActionAuditStore store;
    private final Clock clock;
    private final Supplier<UUID> identifiers;
    private final TransactionTemplate transactions;

    @Autowired
    ContainerActionAuditTransactions(
            ContainerActionAuditStore store,
            PlatformTransactionManager transactionManager) {
        this(store, transactionManager, Clock.systemUTC(), UUID::randomUUID);
    }

    ContainerActionAuditTransactions(
            ContainerActionAuditStore store,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Supplier<UUID> identifiers) {
        this.store = store;
        this.clock = clock;
        this.identifiers = identifiers;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    ContainerActionReservation reserve(
            String idempotencyKey,
            String principal,
            String containerId,
            ContainerControlOperation operation) {
        Instant requestedAt = PostgresqlTimestamp.canonicalize(clock.instant());
        UUID operationId = requiredIdentifier(identifiers.get());
        return required(transactions.execute(transaction -> {
            boolean created = store.insertRequested(
                    operationId,
                    idempotencyKey,
                    principal,
                    containerId,
                    operation,
                    requestedAt);
            ContainerActionAuditRecord record = store.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Container action reservation is unavailable"));
            return new ContainerActionReservation(record, created);
        }));
    }

    Optional<ContainerActionAuditRecord> findByIdempotencyKey(String idempotencyKey) {
        return required(transactions.execute(
                transaction -> store.findByIdempotencyKey(idempotencyKey)));
    }

    Optional<ContainerActionAuditRecord> find(UUID operationId) {
        return required(transactions.execute(transaction -> store.findById(operationId)));
    }

    boolean complete(
            UUID operationId,
            ContainerActionStatus status,
            String reasonCode,
            Instant completedAt) {
        Instant canonicalCompletedAt = PostgresqlTimestamp.canonicalize(completedAt);
        return Boolean.TRUE.equals(transactions.execute(transaction ->
                store.completeRequested(
                        operationId,
                        status,
                        reasonCode,
                        canonicalCompletedAt)));
    }

    int reconcileStaleRequested() {
        Instant now = PostgresqlTimestamp.canonicalize(clock.instant());
        Instant cutoff = PostgresqlTimestamp.canonicalize(now.minus(STALE_REQUEST_AFTER));
        return required(transactions.execute(transaction ->
                store.reconcileStaleRequested(
                        cutoff,
                        now,
                        RECONCILIATION_BATCH_SIZE)));
    }

    private static UUID requiredIdentifier(UUID identifier) {
        if (identifier == null) {
            throw new IllegalStateException("Container action operation ID is unavailable");
        }
        return identifier;
    }

    private static <T> T required(T value) {
        if (value == null) {
            throw new IllegalStateException("Container action transaction returned no result");
        }
        return value;
    }
}
