package dev.homeops.recovery;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

record AutomaticRecoveryMapping(
        UUID serviceId,
        AutomaticRecoveryProject project,
        AutomaticRecoveryTarget target,
        boolean enabled,
        Instant lastReservedAt) {

    AutomaticRecoveryMapping {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(target, "target");
    }
}
