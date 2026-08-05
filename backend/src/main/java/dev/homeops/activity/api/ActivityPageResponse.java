package dev.homeops.activity.api;

import java.time.Instant;
import java.util.List;

public record ActivityPageResponse(
        List<ActivityEventResponse> items,
        String nextCursor,
        Instant generatedAt) { }
