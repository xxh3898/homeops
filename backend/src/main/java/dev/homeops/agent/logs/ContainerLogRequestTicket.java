package dev.homeops.agent.logs;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public record ContainerLogRequestTicket(
        UUID requestId,
        CompletionStage<ContainerLogResult> result) {
}
