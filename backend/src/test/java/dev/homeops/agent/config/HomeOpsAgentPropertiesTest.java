package dev.homeops.agent.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HomeOpsAgentPropertiesTest {

    @Test
    void should_rejectRetention_when_notLongerThanMaximumSnapshotAge() {
        assertThatThrownBy(() -> new HomeOpsAgentProperties(
                "local-mac",
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                Duration.ofMinutes(1),
                128,
                Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Processed snapshot retention must be longer");
    }

    @Test
    void should_rejectInstantArithmeticWindows_when_valuesAreOutsideSupportedBounds() {
        assertThatThrownBy(() -> properties(Duration.ZERO, Duration.ofMinutes(5), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Agent stale threshold must be positive and at most 30 days");
        assertThatThrownBy(() -> properties(Duration.ofDays(31), Duration.ofMinutes(5), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Agent stale threshold must be positive and at most 30 days");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(30), Duration.ZERO, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Agent maximum snapshot age must be positive");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(16)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Agent allowed future skew must be between zero and 15 minutes");
    }

    private static HomeOpsAgentProperties properties(
            Duration staleAfter, Duration maximumSnapshotAge, Duration allowedFutureSkew) {
        return new HomeOpsAgentProperties("local-mac", staleAfter, maximumSnapshotAge,
                allowedFutureSkew, 128, Duration.ofDays(1));
    }
}
