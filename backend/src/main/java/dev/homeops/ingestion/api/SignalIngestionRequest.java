package dev.homeops.ingestion.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import dev.homeops.common.PostgresqlTimestamp;
import dev.homeops.common.validation.NoPostgresqlNul;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public record SignalIngestionRequest(
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        @NoPostgresqlNul String eventKey,
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        @NoPostgresqlNul String episodeKey,
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        @NoPostgresqlNul String project,
        @NotNull SignalType signalType,
        @NotNull SignalStatus status,
        @NotNull Instant observedAt,
        @Digits(integer = 3, fraction = 2) @DecimalMin("0") @DecimalMax("100") BigDecimal availablePercent,
        @Digits(integer = 3, fraction = 2) @DecimalMin(value = "0", inclusive = false)
        @DecimalMax("100") BigDecimal thresholdPercent,
        @PositiveOrZero @Max(1_000_000) Integer count,
        @Positive @Max(86_400) Integer windowSeconds,
        @Positive @Max(1_000_000) Integer thresholdCount) {

    @AssertTrue(message = "Signal timestamps must be within PostgreSQL's supported range")
    public boolean isPostgresqlTimestampRangeSupported() {
        return observedAt == null || PostgresqlTimestamp.isSupported(observedAt);
    }

    @AssertTrue(message = "Signal measurements must match the signal type")
    public boolean isMeasurementShapeValid() {
        if (signalType == null) {
            return true;
        }
        return switch (signalType) {
            case DISK_LOW -> availablePercent != null && thresholdPercent != null
                    && count == null && windowSeconds == null && thresholdCount == null;
            case HTTP_5XX_BURST -> availablePercent == null && thresholdPercent == null
                    && count != null && windowSeconds != null && thresholdCount != null;
        };
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported signal ingestion field");
    }

    public enum SignalType {
        DISK_LOW,
        HTTP_5XX_BURST
    }

    public enum SignalStatus {
        ALERT,
        RECOVERED
    }
}
