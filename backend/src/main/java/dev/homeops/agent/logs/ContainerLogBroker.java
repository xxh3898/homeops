package dev.homeops.agent.logs;

import dev.homeops.agent.AgentSnapshotService;
import dev.homeops.agent.logs.api.AgentLogResultRequest;
import dev.homeops.system.AmbiguousContainerIdentifierException;
import dev.homeops.system.ContainerInventoryUnavailableException;
import dev.homeops.system.ContainerNotFoundException;
import dev.homeops.system.InvalidContainerIdentifierException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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
public class ContainerLogBroker {

    static final int MAXIMUM_ACTIVE_REQUESTS = 4;
    static final int MAXIMUM_ACTIVE_AGENT_WORK = 1;
    static final int MAXIMUM_TOMBSTONES = 16;
    static final int MAXIMUM_LINES = 200;
    static final int MAXIMUM_MESSAGE_BYTES = 128 * 1024;
    static final Duration REQUEST_TTL = Duration.ofSeconds(6);
    static final Duration TOMBSTONE_TTL = Duration.ofSeconds(30);
    static final Duration LONG_POLL = Duration.ofSeconds(2);

    private final AgentSnapshotService snapshotService;
    private final ContainerLogRedactor redactor;
    private final Clock clock;
    private final Supplier<UUID> identifiers;
    private final Duration longPoll;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition workAvailable = lock.newCondition();
    private final Map<UUID, RequestEntry> requests = new LinkedHashMap<>();
    private final ArrayDeque<UUID> pending = new ArrayDeque<>();
    private final LinkedHashMap<UUID, Tombstone> tombstones = new LinkedHashMap<>();

    @Autowired
    public ContainerLogBroker(
            AgentSnapshotService snapshotService,
            ContainerLogRedactor redactor) {
        this(snapshotService, redactor, Clock.systemUTC(), UUID::randomUUID, LONG_POLL);
    }

    ContainerLogBroker(
            AgentSnapshotService snapshotService,
            ContainerLogRedactor redactor,
            Clock clock,
            Supplier<UUID> identifiers,
            Duration longPoll) {
        this.snapshotService = snapshotService;
        this.redactor = redactor;
        this.clock = clock;
        this.identifiers = identifiers;
        this.longPoll = longPoll;
    }

    public ContainerLogRequestTicket request(String rawContainerId, int tail) {
        if (!allowedTail(tail)) {
            throw new InvalidContainerLogTailException();
        }
        ContainerLogEligibility eligibility = snapshotService
                .authorizeContainerLogs(rawContainerId);
        lock.lock();
        try {
            Instant now = clock.instant();
            cleanupLocked(now);
            if (requests.size() >= MAXIMUM_ACTIVE_REQUESTS) {
                throw new ContainerLogBrokerCapacityException();
            }
            boolean duplicateContainer = requests.values().stream()
                    .anyMatch(entry -> entry.containerId.equals(eligibility.containerId()));
            if (duplicateContainer) {
                throw new ContainerLogRequestConflictException();
            }
            UUID requestId = nextUniqueIdentifier();
            CompletableFuture<ContainerLogResult> result = new CompletableFuture<>();
            RequestEntry entry = new RequestEntry(
                    requestId,
                    eligibility.containerId(),
                    tail,
                    now.plus(REQUEST_TTL),
                    result);
            requests.put(requestId, entry);
            pending.addLast(requestId);
            workAvailable.signalAll();
            return new ContainerLogRequestTicket(requestId, result);
        } finally {
            lock.unlock();
        }
    }

    public Optional<ContainerLogWork> claimNext() {
        long remainingNanos = longPoll.toNanos();
        lock.lock();
        try {
            while (true) {
                cleanupLocked(clock.instant());
                if (claimedCount() < MAXIMUM_ACTIVE_AGENT_WORK) {
                    ContainerLogWork work = claimPendingLocked();
                    if (work != null) {
                        return Optional.of(work);
                    }
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

    public void complete(AgentLogResultRequest request) {
        lock.lock();
        try {
            Instant now = clock.instant();
            cleanupLocked(now);
            Tombstone tombstone = tombstones.get(request.requestId());
            if (tombstone != null) {
                if (tombstone.outcome == TombstoneOutcome.COMPLETED) {
                    return;
                }
                throw new ContainerLogRequestGoneException();
            }
            RequestEntry entry = requests.get(request.requestId());
            if (entry == null || entry.state != RequestState.CLAIMED) {
                throw new ContainerLogRequestGoneException();
            }
            try {
                snapshotService.authorizeContainerLogs(entry.containerId);
            } catch (ContainerInventoryUnavailableException
                    | ContainerNotFoundException
                    | AmbiguousContainerIdentifierException
                    | InvalidContainerIdentifierException
                    | ContainerLogCapabilityUnavailableException
                    | ContainerLogsNotAllowedException exception) {
                requests.remove(entry.requestId);
                entry.result.completeExceptionally(exception);
                addTombstoneLocked(
                        entry.requestId,
                        now,
                        TombstoneOutcome.DENIED);
                workAvailable.signalAll();
                throw new ContainerLogRequestGoneException();
            }
            ContainerLogResult result = validateAndSanitize(request, entry.tail);
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

    private ContainerLogResult validateAndSanitize(
            AgentLogResultRequest request,
            int requestedTail) {
        if (request.status() == null || request.lines() == null
                || request.lines().size() > MAXIMUM_LINES
                || request.lines().size() > requestedTail
                || (request.status() != ContainerLogResultStatus.SUCCESS
                && (!request.lines().isEmpty() || request.truncated()))) {
            throw new ContainerLogResultRejectedException();
        }
        int rawBytes = 0;
        int sanitizedBytes = 0;
        var lines = new java.util.ArrayList<ContainerLogLine>(request.lines().size());
        for (AgentLogResultRequest.Line line : request.lines()) {
            if (line == null || line.stream() == null || line.message() == null) {
                throw new ContainerLogResultRejectedException();
            }
            rawBytes = boundedAdd(rawBytes, line.message());
            String sanitized = redactor.sanitize(line.message());
            sanitizedBytes = boundedAdd(sanitizedBytes, sanitized);
            lines.add(new ContainerLogLine(
                    line.timestamp(),
                    line.stream(),
                    sanitized));
        }
        return new ContainerLogResult(
                request.status(),
                List.copyOf(lines),
                request.truncated());
    }

    private static int boundedAdd(int current, String message) {
        int bytes = message.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAXIMUM_MESSAGE_BYTES
                || current > MAXIMUM_MESSAGE_BYTES - bytes) {
            throw new ContainerLogResultRejectedException();
        }
        return current + bytes;
    }

    private ContainerLogWork claimPendingLocked() {
        while (!pending.isEmpty()) {
            UUID requestId = pending.removeFirst();
            RequestEntry entry = requests.get(requestId);
            if (entry == null || entry.state != RequestState.PENDING) {
                continue;
            }
            try {
                snapshotService.authorizeContainerLogs(entry.containerId);
            } catch (ContainerInventoryUnavailableException
                    | ContainerNotFoundException
                    | AmbiguousContainerIdentifierException
                    | InvalidContainerIdentifierException
                    | ContainerLogCapabilityUnavailableException
                    | ContainerLogsNotAllowedException exception) {
                requests.remove(entry.requestId);
                entry.result.completeExceptionally(exception);
                addTombstoneLocked(
                        entry.requestId,
                        clock.instant(),
                        TombstoneOutcome.DENIED);
                continue;
            }
            entry.state = RequestState.CLAIMED;
            return new ContainerLogWork(
                    entry.requestId,
                    entry.containerId,
                    entry.tail);
        }
        return null;
    }

    private long claimedCount() {
        return requests.values().stream()
                .filter(entry -> entry.state == RequestState.CLAIMED)
                .count();
    }

    private void cleanupLocked(Instant now) {
        Iterator<RequestEntry> iterator = requests.values().iterator();
        while (iterator.hasNext()) {
            RequestEntry entry = iterator.next();
            if (!now.isBefore(entry.expiresAt)) {
                iterator.remove();
                pending.remove(entry.requestId);
                entry.result.completeExceptionally(
                        new ContainerLogRequestExpiredException());
                addTombstoneLocked(entry.requestId, now, TombstoneOutcome.EXPIRED);
            }
        }
        Iterator<Map.Entry<UUID, Tombstone>> tombstoneIterator =
                tombstones.entrySet().iterator();
        while (tombstoneIterator.hasNext()) {
            Map.Entry<UUID, Tombstone> tombstone = tombstoneIterator.next();
            if (!now.isBefore(tombstone.getValue().completedAt.plus(TOMBSTONE_TTL))) {
                tombstoneIterator.remove();
            }
        }
        while (tombstones.size() > MAXIMUM_TOMBSTONES) {
            Iterator<UUID> identifiers = tombstones.keySet().iterator();
            identifiers.next();
            identifiers.remove();
        }
    }

    private void addTombstoneLocked(
            UUID requestId,
            Instant now,
            TombstoneOutcome outcome) {
        tombstones.put(requestId, new Tombstone(now, outcome));
        while (tombstones.size() > MAXIMUM_TOMBSTONES) {
            Iterator<UUID> identifiers = tombstones.keySet().iterator();
            identifiers.next();
            identifiers.remove();
        }
    }

    private UUID nextUniqueIdentifier() {
        for (int attempt = 0; attempt < 8; attempt++) {
            UUID candidate = identifiers.get();
            if (!requests.containsKey(candidate) && !tombstones.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new ContainerLogBrokerCapacityException();
    }

    private static boolean allowedTail(int tail) {
        return tail == 50 || tail == 100 || tail == 200;
    }

    private enum RequestState {
        PENDING,
        CLAIMED
    }

    private enum TombstoneOutcome {
        COMPLETED,
        EXPIRED,
        DENIED
    }

    private record Tombstone(Instant completedAt, TombstoneOutcome outcome) {
    }

    private static final class RequestEntry {
        private final UUID requestId;
        private final String containerId;
        private final int tail;
        private final Instant expiresAt;
        private final CompletableFuture<ContainerLogResult> result;
        private RequestState state = RequestState.PENDING;

        private RequestEntry(
                UUID requestId,
                String containerId,
                int tail,
                Instant expiresAt,
                CompletableFuture<ContainerLogResult> result) {
            this.requestId = requestId;
            this.containerId = containerId;
            this.tail = tail;
            this.expiresAt = expiresAt;
            this.result = result;
        }
    }
}
