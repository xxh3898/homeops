package dev.homeops.monitoring.api;

import dev.homeops.common.validation.NoPostgresqlNul;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record MonitoredServiceRequest(
        @NotBlank @Size(max = 128) @NoPostgresqlNul String name,
        @NotBlank @Size(max = 2048) @Pattern(regexp = "https?://[^\\s]+") @NoPostgresqlNul String url,
        @NotNull Method method,
        @Min(100) @Max(599) int expectedStatus,
        @Min(100) @Max(60000) int timeoutMs,
        @Min(5) @Max(86400) int intervalSeconds,
        @Min(1) @Max(100) int failureThreshold,
        @Min(1) @Max(100) int recoveryThreshold,
        @NotNull Severity severity,
        boolean enabled,
        boolean notificationEnabled) {
    public enum Method { GET, HEAD }
    public enum Severity { INFO, WARNING, CRITICAL }
}
