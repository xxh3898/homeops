package dev.homeops.monitoring.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;

public record MonitoredServiceNotificationRequest(@NotNull Boolean enabled) {

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown monitored service notification field");
    }
}
