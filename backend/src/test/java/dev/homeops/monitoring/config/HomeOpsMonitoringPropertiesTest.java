package dev.homeops.monitoring.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class HomeOpsMonitoringPropertiesTest {
    @Test
    void should_rejectConfiguration_when_retentionExceedsBound() {
        assertThatThrownBy(() -> new HomeOpsMonitoringProperties(
                List.of(), Duration.ofDays(366), Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Check result retention must not exceed 365 days");
    }
}
