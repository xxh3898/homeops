package dev.homeops.agent.logs;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public record ContainerLogRequestTicket(
        UUID requestId,
        Instant expiresAt,
        CompletionStage<ContainerLogResult> result) {
}
