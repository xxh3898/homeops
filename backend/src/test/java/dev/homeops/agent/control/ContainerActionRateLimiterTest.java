package dev.homeops.agent.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ContainerActionRateLimiterTest {

    @Test
    void should_allowFiveRequestsAndExpireWindow_when_principalRepeats() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T15:00:00Z"));
        ContainerActionRateLimiter limiter = new ContainerActionRateLimiter(clock);

        for (int request = 0; request < ContainerActionRateLimiter.MAXIMUM_REQUESTS; request++) {
            assertThat(limiter.tryAcquire("admin@example.test")).isTrue();
        }
        assertThat(limiter.tryAcquire("admin@example.test")).isFalse();

        clock.advance(ContainerActionRateLimiter.WINDOW);

        assertThat(limiter.tryAcquire("admin@example.test")).isTrue();
        assertThat(limiter.principalCount()).isOne();
    }

    @Test
    void should_failClosedAtBoundedPrincipalCapacity_when_allEntriesAreActive() {
        ContainerActionRateLimiter limiter = new ContainerActionRateLimiter(
                Clock.fixed(Instant.parse("2026-08-20T15:00:00Z"), ZoneId.of("UTC")));

        for (int principal = 0;
                principal < ContainerActionRateLimiter.MAXIMUM_PRINCIPALS;
                principal++) {
            assertThat(limiter.tryAcquire("admin-" + principal)).isTrue();
        }

        assertThat(limiter.tryAcquire("overflow-admin")).isFalse();
        assertThat(limiter.principalCount())
                .isEqualTo(ContainerActionRateLimiter.MAXIMUM_PRINCIPALS);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
