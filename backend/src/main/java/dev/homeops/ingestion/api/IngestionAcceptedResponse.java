package dev.homeops.ingestion.api;

import java.util.UUID;

public record IngestionAcceptedResponse(UUID id, boolean duplicate) {
}
