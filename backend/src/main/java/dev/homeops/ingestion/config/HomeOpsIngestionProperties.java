package dev.homeops.ingestion.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("homeops.ingestion")
public record HomeOpsIngestionProperties(
        String sharedSecret,
        @NotNull Duration maximumRequestAge,
        @NotNull Duration allowedFutureSkew) {

    public HomeOpsIngestionProperties {
        if (sharedSecret != null && !sharedSecret.isEmpty()
                && !sharedSecret.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Ingestion shared secret must be empty or 64 lowercase hexadecimal characters");
        }
        if (maximumRequestAge != null && (maximumRequestAge.isNegative() || maximumRequestAge.isZero())) {
            throw new IllegalArgumentException("Ingestion maximum request age must be positive");
        }
        if (allowedFutureSkew != null && allowedFutureSkew.isNegative()) {
            throw new IllegalArgumentException("Ingestion allowed future skew must not be negative");
        }
    }

    public boolean isConfigured() {
        return sharedSecret != null && !sharedSecret.isEmpty();
    }
}
