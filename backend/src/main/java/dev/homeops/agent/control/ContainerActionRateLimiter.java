package dev.homeops.agent.control;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class ContainerActionRateLimiter {
    static final int MAXIMUM_REQUESTS = 5;
    static final int MAXIMUM_PRINCIPALS = 32;
    static final Duration WINDOW = Duration.ofSeconds(60);

    private final Clock clock;
    private final Map<String, ArrayDeque<Instant>> requests = new LinkedHashMap<>();

    @Autowired
    ContainerActionRateLimiter() {
        this(Clock.systemUTC());
    }

    ContainerActionRateLimiter(Clock clock) {
        this.clock = clock;
    }

    synchronized boolean tryAcquire(String principal) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        cleanup(cutoff);
        ArrayDeque<Instant> principalRequests = requests.get(principal);
        if (principalRequests == null) {
            if (requests.size() >= MAXIMUM_PRINCIPALS) {
                return false;
            }
            principalRequests = new ArrayDeque<>();
            requests.put(principal, principalRequests);
        }
        if (principalRequests.size() >= MAXIMUM_REQUESTS) {
            return false;
        }
        principalRequests.addLast(now);
        return true;
    }

    synchronized int principalCount() {
        cleanup(clock.instant().minus(WINDOW));
        return requests.size();
    }

    private void cleanup(Instant cutoff) {
        Iterator<ArrayDeque<Instant>> entries = requests.values().iterator();
        while (entries.hasNext()) {
            ArrayDeque<Instant> timestamps = entries.next();
            while (!timestamps.isEmpty() && !timestamps.getFirst().isAfter(cutoff)) {
                timestamps.removeFirst();
            }
            if (timestamps.isEmpty()) {
                entries.remove();
            }
        }
    }
}
