package dev.homeops.recovery;

import dev.homeops.agent.AgentSnapshotService;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class AutomaticRecoveryDispatcher {
    private final AutomaticRecoveryTransactions transactions;
    private final AutomaticRecoveryBroker broker;
    private final AgentSnapshotService agentSnapshots;
    private final Executor auditExecutor;
    private final Clock clock;

    @Autowired
    AutomaticRecoveryDispatcher(
            AutomaticRecoveryTransactions transactions,
            AutomaticRecoveryBroker broker,
            AgentSnapshotService agentSnapshots,
            @Qualifier("automaticRecoveryAuditExecutor") Executor auditExecutor) {
        this(transactions, broker, agentSnapshots, auditExecutor, Clock.systemUTC());
    }

    AutomaticRecoveryDispatcher(
            AutomaticRecoveryTransactions transactions,
            AutomaticRecoveryBroker broker,
            AgentSnapshotService agentSnapshots,
            Executor auditExecutor,
            Clock clock) {
        this.transactions = transactions;
        this.broker = broker;
        this.agentSnapshots = agentSnapshots;
        this.auditExecutor = auditExecutor;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 500)
    void dispatchPending() {
        Instant now = clock.instant();
        transactions.reconcileStaleDispatched(now);
        AutomaticRecoveryAttempt attempt = transactions.claimNext(
                now,
                agentSnapshots.hasFreshRhaomiRecoveryCapability()).orElse(null);
        if (attempt == null) {
            return;
        }
        AutomaticRecoveryRequestTicket ticket;
        try {
            ticket = broker.enqueue(
                    attempt.id(),
                    attempt.project(),
                    attempt.target(),
                    attempt.action());
        } catch (AutomaticRecoveryBrokerCapacityException
                | AutomaticRecoveryRequestConflictException exception) {
            transactions.completeWithoutResult(
                    attempt.id(),
                    AutomaticRecoveryStatus.SKIPPED,
                    AutomaticRecoveryReasonCode.BROKER_BUSY,
                    now);
            return;
        }
        ticket.result().whenCompleteAsync(
                (result, failure) -> projectResult(attempt.id(), result, failure),
                auditExecutor);
    }

    private void projectResult(
            java.util.UUID attemptId,
            AutomaticRecoveryResult result,
            Throwable failure) {
        if (failure == null && result != null) {
            transactions.complete(attemptId, result);
            return;
        }
        transactions.completeWithoutResult(
                attemptId,
                AutomaticRecoveryStatus.OUTCOME_UNKNOWN,
                AutomaticRecoveryReasonCode.RESULT_UNAVAILABLE,
                clock.instant());
    }

}
