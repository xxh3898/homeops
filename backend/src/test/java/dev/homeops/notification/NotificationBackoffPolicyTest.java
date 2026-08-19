package dev.homeops.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NotificationBackoffPolicyTest {

    @Test
    void should_applyExponentialBackoffAndPositiveJitter_when_attemptsIncrease() {
        NotificationBackoffPolicy policy = new NotificationBackoffPolicy(
                Duration.ofSeconds(5), Duration.ofMinutes(15), () -> 0.5);

        assertThat(policy.delay(1, Optional.empty())).isEqualTo(Duration.ofMillis(5_500));
        assertThat(policy.delay(2, Optional.empty())).isEqualTo(Duration.ofSeconds(11));
        assertThat(policy.delay(20, Optional.empty())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void should_useServerDelayAsMinimum_when_rateLimitDelayIsLonger() {
        NotificationBackoffPolicy policy = new NotificationBackoffPolicy(
                Duration.ofSeconds(5), Duration.ofMinutes(15), () -> 0.0);

        assertThat(policy.delay(1, Optional.of(Duration.ofSeconds(20))))
                .isEqualTo(Duration.ofSeconds(20));
    }
}
