package dev.homeops.agent.control;

import dev.homeops.agent.ContainerControlAuthority;
import dev.homeops.agent.ContainerControlAuthority.Decision;
import dev.homeops.agent.ContainerControlAuthority.Target;
import dev.homeops.agent.control.api.AgentControlResultRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ContainerControlBroker {

    static final int MAXIMUM_ACTIVE_REQUESTS = 1;
    static final int MAXIMUM_TOMBSTONES = 16;
    static final Duration REQUEST_TTL = Duration.ofSeconds(15);
    static final Duration RESULT_TIMESTAMP_SKEW = Duration.ofSeconds(1);
    static final Duration TOMBSTONE_TTL = Duration.ofSeconds(30);
    static final Duration LONG_POLL = Duration.ofSeconds(2);

    private final ContainerControlAuthority authority;
    private final Clock clock;
    private final Supplier<UUID> identifiers;
    private final Duration longPoll;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition workAvailable = lock.newCondition();
    private final Map<UUID, RequestEntry> requests = new LinkedHashMap<>();
    private final ArrayDeque<UUID> pending = new ArrayDeque<>();
    private final LinkedHashMap<UUID, Tombstone> tombstones = new LinkedHashMap<>();

    @Autowired
    public ContainerControlBroker(ContainerControlAuthority authority) {
        this(authority, Clock.systemUTC(), UUID::randomUUID, LONG_POLL);
    }

    ContainerControlBroker(
            ContainerControlAuthority authority,
            Clock clock,
            Supplier<UUID> identifiers,
            Duration longPoll) {
        this.authority = authority;
        this.clock = clock;
        this.identifiers = identifiers;
        this.longPoll = longPoll;
    }

    public ContainerControlRequestTicket enqueue(
            String rawContainerId,
            ContainerControlOperation operation) {
        if (operation == null) {
            throw new IllegalArgumentException("Container control operation is required");
        }
        Target target = requireEligible(authority.evaluate(rawContainerId));
        lock.lock();
        try {
            Instant now = clock.instant();
            cleanupLocked(now);
            boolean duplicateContainer = requests.values().stream()
                    .anyMatch(entry -> entry.containerId.equals(target.containerId()));
            if (duplicateContainer) {
                throw new ContainerControlRequestConflictException();
            }
            if (requests.size() >= MAXIMUM_ACTIVE_REQUESTS) {
                throw new ContainerControlBrokerCapacityException();
            }
            UUID requestId = nextUniqueIdentifier();
            CompletableFuture<ContainerControlResult> result = new CompletableFuture<>();
            RequestEntry entry = new RequestEntry(
                    requestId,
                    target.containerId(),
                    target.composeProject(),
                    operation,
                    now,
                    now.plus(REQUEST_TTL),
                    result);
            requests.put(requestId, entry);
            pending.addLast(requestId);
            workAvailable.signalAll();
            return new ContainerControlRequestTicket(requestId, entry.expiresAt, result);
        } finally {
            lock.unlock();
        }
    }

    public void cancel(ContainerControlRequestTicket ticket) {
        lock.lock();
        try {
            Instant now = clock.instant();
            cleanupLocked(now);
            RequestEntry entry = requests.get(ticket.requestId());
            if (entry == null || entry.result != ticket.result()) {
                return;
            }
            requests.remove(entry.requestId);
            pending.remove(entry.requestId);
            entry.result.completeExceptionally(
                    new ContainerControlRequestCancelledException());
            addTombstoneLocked(entry.requestId, now, TombstoneOutcome.CANCELLED);
            workAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public Optional<ContainerControlWork> claimNext() {
        long remainingNanos = longPoll.toNanos();
        lock.lock();
        try {
            while (true) {
                cleanupLocked(clock.instant());
                ContainerControlWork work = claimPendingLocked();
                if (work != null) {
                    return Optional.of(work);
                }
                if (remainingNanos <= 0) {
                    return Optional.empty();
                }
                try {
                    remainingNanos = workAvailable.awaitNanos(remainingNanos);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void complete(AgentControlResultRequest request) {
        lock.lock();
        try {
            Instant now = clock.instant();
            cleanupLocked(now);
            Tombstone tombstone = tombstones.get(request.requestId());
            if (tombstone != null) {
                if (tombstone.outcome == TombstoneOutcome.COMPLETED) {
                    return;
                }
                throw new ContainerControlRequestGoneException();
            }
            RequestEntry entry = requests.get(request.requestId());
            if (entry == null || entry.state != RequestState.CLAIMED) {
                throw new ContainerControlRequestGoneException();
            }
            ContainerControlResult result = validateResult(request, entry, now);
            requests.remove(entry.requestId);
            entry.result.complete(result);
            addTombstoneLocked(entry.requestId, now, TombstoneOutcome.COMPLETED);
            workAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Scheduled(fixedDelay = 1000)
    void cleanupExpired() {
        lock.lock();
        try {
            cleanupLocked(clock.instant());
        } finally {
            lock.unlock();
        }
    }

    int activeRequestCount() {
        lock.lock();
        try {
            cleanupLocked(clock.instant());
            return requests.size();
        } finally {
            lock.unlock();
        }
    }

    int tombstoneCount() {
        lock.lock();
        try {
            cleanupLocked(clock.instant());
            return tombstones.size();
        } finally {
            lock.unlock();
        }
    }

    private ContainerControlWork claimPendingLocked() {
        while (!pending.isEmpty()) {
            UUID requestId = pending.removeFirst();
            RequestEntry entry = requests.get(requestId);
            if (entry == null || entry.state != RequestState.PENDING) {
                continue;
            }
            Decision decision = authority.evaluate(entry.containerId);
            if (!decision.eligible()
                    || !sameTarget(decision.target(), entry.containerId, entry.composeProject)) {
                requests.remove(entry.requestId);
                entry.result.completeExceptionally(
                        new ContainerControlDeniedException(decision.code()));
                addTombstoneLocked(entry.requestId, clock.instant(), TombstoneOutcome.DENIED);
                continue;
            }
            entry.state = RequestState.CLAIMED;
            return new ContainerControlWork(
                    entry.requestId,
                    entry.containerId,
                    entry.composeProject,
                    entry.operation,
                    entry.expiresAt);
        }
        return null;
    }

    private static Target requireEligible(Decision decision) {
        if (!decision.eligible()) {
            throw new ContainerControlDeniedException(decision.code());
        }
        return decision.target();
    }

    private static boolean sameTarget(
            Target target,
            String containerId,
            String composeProject) {
        return target != null
                && target.containerId().equals(containerId)
                && target.composeProject().equals(composeProject);
    }

    private static ContainerControlResult validateResult(
            AgentControlResultRequest request,
            RequestEntry entry,
            Instant now) {
        if (request.status() == null
                || request.reasonCode() == null
                || !request.reasonCode().isValidFor(request.status())) {
            throw new ContainerControlResultRejectedException();
        }
        validateFinishedAt(request.finishedAt(), entry, now);
        return new ContainerControlResult(
                request.status(),
                request.reasonCode(),
                request.finishedAt());
    }

    private static void validateFinishedAt(
            Instant finishedAt,
            RequestEntry entry,
            Instant now) {
        if (finishedAt == null) {
            throw new ContainerControlResultRejectedException();
        }
        Instant earliest = entry.createdAt.minus(RESULT_TIMESTAMP_SKEW);
        Instant latestByRequest = entry.expiresAt.plus(RESULT_TIMESTAMP_SKEW);
        Instant latestByServer = now.plus(RESULT_TIMESTAMP_SKEW);
        if (finishedAt.isBefore(earliest)
                || !finishedAt.isBefore(latestByRequest)
                || finishedAt.isAfter(latestByServer)) {
            throw new ContainerControlResultRejectedException();
        }
    }

    private void cleanupLocked(Instant now) {
        Iterator<RequestEntry> iterator = requests.values().iterator();
        while (iterator.hasNext()) {
            RequestEntry entry = iterator.next();
            if (!now.isBefore(entry.expiresAt)) {
                iterator.remove();
                pending.remove(entry.requestId);
                entry.result.complete(new ContainerControlResult(
                        ContainerControlResultStatus.EXPIRED,
                        ContainerControlReasonCode.WORK_EXPIRED,
                        entry.expiresAt));
                addTombstoneLocked(entry.requestId, now, TombstoneOutcome.EXPIRED);
            }
        }
        Iterator<Map.Entry<UUID, Tombstone>> iteratorTombstones =
                tombstones.entrySet().iterator();
        while (iteratorTombstones.hasNext()) {
            Map.Entry<UUID, Tombstone> tombstone = iteratorTombstones.next();
            if (!now.isBefore(tombstone.getValue().completedAt.plus(TOMBSTONE_TTL))) {
                iteratorTombstones.remove();
            }
        }
        trimTombstonesLocked();
    }

    private void addTombstoneLocked(
            UUID requestId,
            Instant now,
            TombstoneOutcome outcome) {
        tombstones.put(requestId, new Tombstone(now, outcome));
        trimTombstonesLocked();
    }

    private void trimTombstonesLocked() {
        while (tombstones.size() > MAXIMUM_TOMBSTONES) {
            Iterator<UUID> identifiers = tombstones.keySet().iterator();
            identifiers.next();
            identifiers.remove();
        }
    }

    private UUID nextUniqueIdentifier() {
        for (int attempt = 0; attempt < 8; attempt++) {
            UUID candidate = identifiers.get();
            if (candidate != null
                    && !requests.containsKey(candidate)
                    && !tombstones.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new ContainerControlBrokerCapacityException();
    }

    private enum RequestState {
        PENDING,
        CLAIMED
    }

    private enum TombstoneOutcome {
        COMPLETED,
        EXPIRED,
        DENIED,
        CANCELLED
    }

    private record Tombstone(Instant completedAt, TombstoneOutcome outcome) {
    }

    private static final class RequestEntry {
        private final UUID requestId;
        private final String containerId;
        private final String composeProject;
        private final ContainerControlOperation operation;
        private final Instant createdAt;
        private final Instant expiresAt;
        private final CompletableFuture<ContainerControlResult> result;
        private RequestState state = RequestState.PENDING;

        private RequestEntry(
                UUID requestId,
                String containerId,
                String composeProject,
                ContainerControlOperation operation,
                Instant createdAt,
                Instant expiresAt,
                CompletableFuture<ContainerControlResult> result) {
            this.requestId = requestId;
            this.containerId = containerId;
            this.composeProject = composeProject;
            this.operation = operation;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.result = result;
        }
    }
}
