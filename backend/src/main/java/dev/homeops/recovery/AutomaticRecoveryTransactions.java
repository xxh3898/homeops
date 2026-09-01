package dev.homeops.recovery;

import dev.homeops.common.PostgresqlTimestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class AutomaticRecoveryTransactions {
    static final Duration REQUEST_MAXIMUM_AGE = Duration.ofSeconds(30);
    static final Duration RECONCILIATION_SAFETY_MARGIN = Duration.ofSeconds(5);
    static final Duration STALE_DISPATCHED_AFTER = AutomaticRecoveryBroker.REQUEST_TTL
            .plus(AutomaticRecoveryBroker.RESULT_REPORTING_GRACE)
            .plus(RECONCILIATION_SAFETY_MARGIN);
    static final int RECONCILIATION_BATCH_SIZE = 16;

    private final AutomaticRecoveryStore store;
    private final TransactionTemplate transactions;

    AutomaticRecoveryTransactions(
            AutomaticRecoveryStore store,
            PlatformTransactionManager transactionManager) {
        this.store = store;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    Optional<AutomaticRecoveryAttempt> claimNext(Instant rawNow, boolean capabilityAvailable) {
        Instant now = PostgresqlTimestamp.canonicalize(rawNow);
        Optional<AutomaticRecoveryAttempt> result = transactions.execute(transaction -> {
            AutomaticRecoveryAttempt attempt = store.findNextRequestedForUpdate().orElse(null);
            if (attempt == null) {
                return Optional.empty();
            }
            if (!now.isBefore(attempt.requestedAt().plus(REQUEST_MAXIMUM_AGE))) {
                store.completeRequested(
                        attempt.id(),
                        AutomaticRecoveryStatus.EXPIRED,
                        AutomaticRecoveryReasonCode.REQUEST_EXPIRED,
                        now);
                return Optional.empty();
            }
            if (!store.incidentOpenForUpdate(attempt.incidentId(), attempt.serviceId())) {
                store.completeRequested(
                        attempt.id(),
                        AutomaticRecoveryStatus.SKIPPED,
                        AutomaticRecoveryReasonCode.INCIDENT_NOT_OPEN,
                        now);
                return Optional.empty();
            }
            AutomaticRecoveryMapping mapping = store.findMappingForUpdate(attempt.serviceId())
                    .orElse(null);
            if (mapping == null
                    || !mapping.enabled()
                    || mapping.project() != attempt.project()
                    || mapping.target() != attempt.target()) {
                store.completeRequested(
                        attempt.id(),
                        AutomaticRecoveryStatus.SKIPPED,
                        AutomaticRecoveryReasonCode.AUTHORITY_DISABLED,
                        now);
                return Optional.empty();
            }
            if (!capabilityAvailable) {
                store.completeRequested(
                        attempt.id(),
                        AutomaticRecoveryStatus.SKIPPED,
                        AutomaticRecoveryReasonCode.CAPABILITY_UNAVAILABLE,
                        now);
                return Optional.empty();
            }
            if (!store.markDispatched(attempt.id(), now)) {
                throw new IllegalStateException("Automatic recovery dispatch claim was lost");
            }
            return store.findById(attempt.id());
        });
        return result == null ? Optional.empty() : result;
    }

    boolean complete(UUID attemptId, AutomaticRecoveryResult result) {
        Boolean updated = transactions.execute(transaction ->
                store.completeDispatched(attemptId, result));
        return Boolean.TRUE.equals(updated);
    }

    boolean completeWithoutResult(
            UUID attemptId,
            AutomaticRecoveryStatus status,
            AutomaticRecoveryReasonCode reason,
            Instant rawCompletedAt) {
        Instant completedAt = PostgresqlTimestamp.canonicalize(rawCompletedAt);
        Boolean updated = transactions.execute(transaction ->
                store.completeDispatchedWithoutResult(
                        attemptId, status, reason, completedAt));
        return Boolean.TRUE.equals(updated);
    }

    int reconcileStaleDispatched(Instant rawNow) {
        Instant now = PostgresqlTimestamp.canonicalize(rawNow);
        Instant cutoff = PostgresqlTimestamp.canonicalize(now.minus(STALE_DISPATCHED_AFTER));
        Integer updated = transactions.execute(transaction ->
                store.reconcileStaleDispatched(
                        cutoff,
                        now,
                        RECONCILIATION_BATCH_SIZE));
        return updated == null ? 0 : updated;
    }

}
