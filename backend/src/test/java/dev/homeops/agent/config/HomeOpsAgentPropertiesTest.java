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
}
