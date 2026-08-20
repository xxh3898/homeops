package dev.homeops.agent.control;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public record ContainerControlRequestTicket(
        UUID requestId,
        Instant expiresAt,
        CompletionStage<ContainerControlResult> result) {
}
