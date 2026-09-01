package dev.homeops.recovery;

import dev.homeops.recovery.api.AgentRecoveryResultRequest;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AutomaticRecoveryBroker {
    static final int MAXIMUM_ACTIVE_REQUESTS = 1;
    static final int MAXIMUM_TOMBSTONES = 16;
    static final Duration REQUEST_TTL = Duration.ofSeconds(10);
    static final Duration RESULT_REPORTING_GRACE = Duration.ofSeconds(185);
    static final Duration RESULT_TIMESTAMP_SKEW = Duration.ofSeconds(1);
    static final Duration TOMBSTONE_TTL = Duration.ofSeconds(30);
    static final Duration LONG_POLL = Duration.ofSeconds(2);

    private final Clock clock;
    private final Duration longPoll;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition workAvailable = lock.newCondition();
    private final Map<UUID, RequestEntry> requests = new LinkedHashMap<>();
    private final ArrayDeque<UUID> pending = new ArrayDeque<>();
    private final LinkedHashMap<UUID, Tombstone> tombstones = new LinkedHashMap<>();

    @Autowired
    public AutomaticRecoveryBroker() {
        this(Clock.systemUTC(), LONG_POLL);
    }

    AutomaticRecoveryBroker(Clock clock, Duration longPoll) {
        this.clock = clock;
        this.longPoll = longPoll;
    }

    AutomaticRecoveryRequestTicket enqueue(
            UUID requestId,
            AutomaticRecoveryProject project,
            AutomaticRecoveryTarget target,
            AutomaticRecoveryAction action) {
        if (requestId == null || project == null || target == null || action == null) {
            throw new IllegalArgumentException("Automatic recovery work is incomplete");
        }
        lock.lock();
        try {
            Instant now = clock.instant();
            cleanupLocked(now);
            if (requests.containsKey(requestId) || tombstones.containsKey(requestId)) {
                throw new AutomaticRecoveryRequestConflictException();
            }
            if (requests.size() >= MAXIMUM_ACTIVE_REQUESTS) {
                throw new AutomaticRecoveryBrokerCapacityException();
            }
            CompletableFuture<AutomaticRecoveryResult> result = new CompletableFuture<>();
            RequestEntry entry = new RequestEntry(
                    requestId,
                    project,
                    target,
                    action,
                    now,
                    now.plus(REQUEST_TTL),
                    result);
            requests.put(requestId, entry);
            pending.addLast(requestId);
            workAvailable.signalAll();
            return new AutomaticRecoveryRequestTicket(requestId, entry.expiresAt, result);
        } finally {
            lock.unlock();
        }
    }

    public Optional<AutomaticRecoveryWork> claimNext() {
        long remainingNanos = longPoll.toNanos();
        lock.lock();
        try {
            while (true) {
                cleanupLocked(clock.instant());
                AutomaticRecoveryWork work = claimPendingLocked();
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

    public void complete(AgentRecoveryResultRequest request) {
        if (request == null || request.requestId() == null) {
            throw new AutomaticRecoveryResultRejectedException();
        }
        lock.lock();
        try {
            Instant now = clock.instant();
            cleanupLocked(now);
            Tombstone tombstone = tombstones.get(request.requestId());
            if (tombstone != null) {
                if (tombstone.outcome == TombstoneOutcome.COMPLETED) {
                    return;
                }
                throw new AutomaticRecoveryRequestGoneException();
            }
            RequestEntry entry = requests.get(request.requestId());
            if (entry == null || entry.state != RequestState.CLAIMED) {
                throw new AutomaticRecoveryRequestGoneException();
            }
            AutomaticRecoveryResult result = validateResult(request, entry, now);
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

    private AutomaticRecoveryWork claimPendingLocked() {
        while (!pending.isEmpty()) {
            UUID requestId = pending.removeFirst();
            RequestEntry entry = requests.get(requestId);
            if (entry == null || entry.state != RequestState.PENDING) {
                continue;
            }
            entry.state = RequestState.CLAIMED;
            return new AutomaticRecoveryWork(
                    entry.requestId,
                    entry.project,
                    entry.target,
                    entry.action,
                    entry.expiresAt);
        }
        return null;
    }

    private static AutomaticRecoveryResult validateResult(
            AgentRecoveryResultRequest request,
            RequestEntry entry,
            Instant now) {
        if (request.status() == null
                || request.reasonCode() == null
                || request.preHealth() == null
                || request.postHealth() == null
                || !request.reasonCode().isValidFor(request.status())) {
            throw new AutomaticRecoveryResultRejectedException();
        }
        validateTimestamps(request.startedAt(), request.finishedAt(), entry, now);
        try {
            return new AutomaticRecoveryResult(
                    request.status(),
                    request.reasonCode(),
                    request.startedAt(),
                    request.finishedAt(),
                    request.preHealth(),
                    request.postHealth(),
                    request.restartCount());
        } catch (IllegalArgumentException exception) {
            throw new AutomaticRecoveryResultRejectedException();
        }
    }

    private static void validateTimestamps(
            Instant startedAt,
            Instant finishedAt,
            RequestEntry entry,
            Instant now) {
        if (startedAt == null || finishedAt == null) {
            throw new AutomaticRecoveryResultRejectedException();
        }
        Instant earliestStart = entry.createdAt.minus(RESULT_TIMESTAMP_SKEW);
        Instant latestStart = entry.expiresAt.plus(RESULT_TIMESTAMP_SKEW);
        Instant latestFinish = entry.resultReportingDeadline().plus(RESULT_TIMESTAMP_SKEW);
        if (startedAt.isBefore(earliestStart)
                || !startedAt.isBefore(latestStart)
                || finishedAt.isBefore(startedAt)
                || finishedAt.isAfter(latestFinish)
                || finishedAt.isAfter(now.plus(RESULT_TIMESTAMP_SKEW))) {
            throw new AutomaticRecoveryResultRejectedException();
        }
    }

    private void cleanupLocked(Instant now) {
        Iterator<RequestEntry> iterator = requests.values().iterator();
        while (iterator.hasNext()) {
            RequestEntry entry = iterator.next();
            if (entry.state == RequestState.PENDING && !now.isBefore(entry.expiresAt)) {
                iterator.remove();
                pending.remove(entry.requestId);
                entry.result.complete(new AutomaticRecoveryResult(
                        AutomaticRecoveryResultStatus.EXPIRED,
                        AutomaticRecoveryReasonCode.WORK_EXPIRED,
                        entry.createdAt,
                        entry.expiresAt,
                        AutomaticRecoveryHealth.UNKNOWN,
                        AutomaticRecoveryHealth.UNKNOWN,
                        0));
                addTombstoneLocked(entry.requestId, now, TombstoneOutcome.EXPIRED);
            } else if (entry.state == RequestState.CLAIMED
                    && !now.isBefore(entry.resultReportingDeadline())) {
                iterator.remove();
                entry.result.complete(new AutomaticRecoveryResult(
                        AutomaticRecoveryResultStatus.OUTCOME_UNKNOWN,
                        AutomaticRecoveryReasonCode.RESULT_UNAVAILABLE,
                        entry.createdAt,
                        entry.resultReportingDeadline(),
                        AutomaticRecoveryHealth.UNKNOWN,
                        AutomaticRecoveryHealth.UNKNOWN,
                        0));
                addTombstoneLocked(entry.requestId, now, TombstoneOutcome.RESULT_UNAVAILABLE);
            }
        }
        Iterator<Map.Entry<UUID, Tombstone>> tombstoneIterator = tombstones.entrySet().iterator();
        while (tombstoneIterator.hasNext()) {
            Map.Entry<UUID, Tombstone> tombstone = tombstoneIterator.next();
            if (!now.isBefore(tombstone.getValue().completedAt.plus(TOMBSTONE_TTL))) {
                tombstoneIterator.remove();
            }
        }
        trimTombstonesLocked();
    }

    private void addTombstoneLocked(UUID requestId, Instant now, TombstoneOutcome outcome) {
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

    private enum RequestState {
        PENDING,
        CLAIMED
    }

    private enum TombstoneOutcome {
        COMPLETED,
        EXPIRED,
        RESULT_UNAVAILABLE
    }

    private record Tombstone(Instant completedAt, TombstoneOutcome outcome) {
    }

    private static final class RequestEntry {
        private final UUID requestId;
        private final AutomaticRecoveryProject project;
        private final AutomaticRecoveryTarget target;
        private final AutomaticRecoveryAction action;
        private final Instant createdAt;
        private final Instant expiresAt;
        private final CompletableFuture<AutomaticRecoveryResult> result;
        private RequestState state = RequestState.PENDING;

        private RequestEntry(
                UUID requestId,
                AutomaticRecoveryProject project,
                AutomaticRecoveryTarget target,
                AutomaticRecoveryAction action,
                Instant createdAt,
                Instant expiresAt,
                CompletableFuture<AutomaticRecoveryResult> result) {
            this.requestId = requestId;
            this.project = project;
            this.target = target;
            this.action = action;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.result = result;
        }

        private Instant resultReportingDeadline() {
            return expiresAt.plus(RESULT_REPORTING_GRACE);
        }
    }
}
