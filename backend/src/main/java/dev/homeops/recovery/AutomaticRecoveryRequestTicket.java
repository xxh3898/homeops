package dev.homeops.recovery;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

record AutomaticRecoveryRequestTicket(
        UUID requestId,
        Instant expiresAt,
        CompletableFuture<AutomaticRecoveryResult> result) {
}
