package dev.homeops.agent;

import java.time.Duration;
import java.time.Instant;

final class AgentFreshness {
    private AgentFreshness() { }

    static boolean isStale(
            Instant capturedAt,
            Instant receivedAt,
            Instant now,
            Duration staleAfter) {
        return capturedAt == null
                || receivedAt == null
                || capturedAt.isBefore(now.minus(staleAfter))
                || receivedAt.isBefore(now.minus(staleAfter));
    }

    static Instant staleSince(Instant capturedAt, Instant receivedAt) {
        if (capturedAt == null) {
            return receivedAt;
        }
        if (receivedAt == null) {
            return capturedAt;
        }
        return capturedAt.isBefore(receivedAt) ? capturedAt : receivedAt;
    }
}
