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
    }

    public boolean isConfigured() {
        return sharedSecret != null && !sharedSecret.isEmpty();
    }
}
