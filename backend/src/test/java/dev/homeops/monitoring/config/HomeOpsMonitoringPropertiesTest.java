package dev.homeops.monitoring.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class HomeOpsMonitoringPropertiesTest {
    @Test
    void should_rejectConfiguration_when_retentionExceedsBound() {
        assertThatThrownBy(() -> new HomeOpsMonitoringProperties(
                List.of(), Duration.ofDays(366), Duration.ofDays(30), 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Check result retention must not exceed 365 days");
    }

    @Test
    void should_rejectConfiguration_when_concurrentChecksExceedBound() {
        assertThatThrownBy(() -> new HomeOpsMonitoringProperties(
                List.of(), Duration.ofDays(7), Duration.ofDays(30), 17))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Maximum concurrent checks must be between 1 and 16");
    }
}
