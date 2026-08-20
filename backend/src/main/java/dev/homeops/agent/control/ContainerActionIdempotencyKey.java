package dev.homeops.agent.control;

import java.util.UUID;

public record ContainerActionIdempotencyKey(String value) {

    public ContainerActionIdempotencyKey {
        if (value == null || value.length() != 36) {
            throw ContainerActionException.invalidRequest();
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw ContainerActionException.invalidRequest();
        }
        if (!parsed.toString().equals(value)) {
            throw ContainerActionException.invalidRequest();
        }
    }

    public static ContainerActionIdempotencyKey parse(String value) {
        return new ContainerActionIdempotencyKey(value);
    }
}
