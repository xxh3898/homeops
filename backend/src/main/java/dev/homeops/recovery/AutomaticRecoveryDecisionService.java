package dev.homeops.recovery;

import dev.homeops.common.PostgresqlTimestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AutomaticRecoveryDecisionService {
    public static final Duration COOLDOWN = Duration.ofMinutes(30);

    private final AutomaticRecoveryStore store;
    private final Supplier<UUID> identifiers;
    private final TransactionTemplate transactions;

    @Autowired
    public AutomaticRecoveryDecisionService(
            AutomaticRecoveryStore store,
            PlatformTransactionManager transactionManager) {
        this(store, transactionManager, UUID::randomUUID);
    }

    AutomaticRecoveryDecisionService(
            AutomaticRecoveryStore store,
            PlatformTransactionManager transactionManager,
            Supplier<UUID> identifiers) {
        this.store = store;
        this.identifiers = identifiers;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Decision evaluateOpenIncident(UUID incidentId, UUID serviceId, Instant openedAt) {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(serviceId, "serviceId");
        Instant requestedAt = PostgresqlTimestamp.canonicalize(
                Objects.requireNonNull(openedAt, "openedAt"));
        Decision decision = transactions.execute(transaction -> evaluate(
                incidentId,
                serviceId,
                requestedAt));
        if (decision == null) {
            throw new IllegalStateException("Automatic recovery decision returned no result");
        }
        return decision;
    }

    private Decision evaluate(UUID incidentId, UUID serviceId, Instant requestedAt) {
        AutomaticRecoveryAttempt existing = store.findByIncident(incidentId).orElse(null);
        if (existing != null) {
            return new Decision(existing, false, false);
        }

        if (!store.incidentOpenForUpdate(incidentId, serviceId)) {
            return insertSkipped(
                    incidentId,
                    serviceId,
                    null,
                    AutomaticRecoveryReasonCode.INCIDENT_NOT_OPEN,
                    requestedAt);
        }

        AutomaticRecoveryMapping mapping = store.findMappingForUpdate(serviceId).orElse(null);
        if (mapping == null) {
            return insertSkipped(
                    incidentId,
                    serviceId,
                    null,
                    AutomaticRecoveryReasonCode.TARGET_UNMAPPED,
                    requestedAt);
        }
        if (!mapping.enabled()) {
            return insertSkipped(
                    incidentId,
                    serviceId,
                    mapping,
                    AutomaticRecoveryReasonCode.AUTHORITY_DISABLED,
                    requestedAt);
        }
        if (cooldownActive(mapping.lastReservedAt(), requestedAt)) {
            return insertSkipped(
                    incidentId,
                    serviceId,
                    mapping,
                    AutomaticRecoveryReasonCode.COOLDOWN_ACTIVE,
                    requestedAt);
        }

        UUID attemptId = requiredIdentifier(identifiers.get());
        boolean created = store.insertRequested(
                attemptId,
                incidentId,
                serviceId,
                mapping,
                requestedAt);
        if (!created) {
            AutomaticRecoveryAttempt winner = store.findByIncident(incidentId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Automatic recovery winner is unavailable"));
            return new Decision(winner, false, false);
        }
        if (!store.updateLastReservedAt(serviceId, requestedAt)) {
            throw new IllegalStateException("Automatic recovery cooldown reservation failed");
        }
        return new Decision(
                store.findByIncident(incidentId).orElseThrow(),
                true,
                true);
    }

    private Decision insertSkipped(
            UUID incidentId,
            UUID serviceId,
            AutomaticRecoveryMapping mapping,
            AutomaticRecoveryReasonCode reason,
            Instant requestedAt) {
        UUID attemptId = requiredIdentifier(identifiers.get());
        boolean created = store.insertSkipped(
                attemptId,
                incidentId,
                serviceId,
                mapping,
                reason,
                requestedAt);
        AutomaticRecoveryAttempt winner = store.findByIncident(incidentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Automatic recovery skip evidence is unavailable"));
        return new Decision(winner, created, false);
    }

    private static boolean cooldownActive(Instant lastReservedAt, Instant requestedAt) {
        return lastReservedAt != null && requestedAt.isBefore(lastReservedAt.plus(COOLDOWN));
    }

    private static UUID requiredIdentifier(UUID identifier) {
        if (identifier == null) {
            throw new IllegalStateException("Automatic recovery attempt identifier is unavailable");
        }
        return identifier;
    }

    public record Decision(
            AutomaticRecoveryAttempt attempt,
            boolean created,
            boolean dispatchEligible) {
    }
}
