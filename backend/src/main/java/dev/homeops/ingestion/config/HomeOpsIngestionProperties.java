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

    public boolean isConfigured() {
        return sharedSecret != null && !sharedSecret.isBlank();
    }
}
