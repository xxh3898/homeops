package dev.homeops.agent.control;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final Map<String, LinkedHashMap<String, Instant>> requests = new LinkedHashMap<>();

    @Autowired
    ContainerActionRateLimiter() {
        this(Clock.systemUTC());
    }

    ContainerActionRateLimiter(Clock clock) {
        this.clock = clock;
    }

    synchronized boolean tryAcquire(String principal, String idempotencyKey) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        cleanup(cutoff);
        LinkedHashMap<String, Instant> principalRequests = requests.get(principal);
        if (principalRequests != null && principalRequests.containsKey(idempotencyKey)) {
            return true;
        }
        if (principalRequests == null) {
            if (requests.size() >= MAXIMUM_PRINCIPALS) {
                return false;
            }
            principalRequests = new LinkedHashMap<>();
            requests.put(principal, principalRequests);
        }
        if (principalRequests.size() >= MAXIMUM_REQUESTS) {
            return false;
        }
        principalRequests.put(idempotencyKey, now);
        return true;
    }

    synchronized int principalCount() {
        cleanup(clock.instant().minus(WINDOW));
        return requests.size();
    }

    private void cleanup(Instant cutoff) {
        Iterator<LinkedHashMap<String, Instant>> entries = requests.values().iterator();
        while (entries.hasNext()) {
            LinkedHashMap<String, Instant> keys = entries.next();
            keys.entrySet().removeIf(entry -> !entry.getValue().isAfter(cutoff));
            if (keys.isEmpty()) {
                entries.remove();
            }
        }
    }
}
