package dev.homeops.agent.control;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ContainerActionReconciliationJob {
    private final ContainerActionAuditTransactions audit;

    ContainerActionReconciliationJob(ContainerActionAuditTransactions audit) {
        this.audit = audit;
    }

    @Scheduled(fixedDelay = 5000)
    void reconcile() {
        audit.reconcileStaleRequested();
    }
}
