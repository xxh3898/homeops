package dev.homeops.agent.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ContainerActionRateLimiterTest {

    @Test
    void should_allowFiveDistinctKeysAndExpireWindow_when_principalRepeats() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T15:00:00Z"));
        ContainerActionRateLimiter limiter = new ContainerActionRateLimiter(clock);

        for (int request = 0; request < ContainerActionRateLimiter.MAXIMUM_REQUESTS; request++) {
            assertThat(limiter.tryAcquire("admin@example.test", "key-" + request)).isTrue();
        }
        assertThat(limiter.tryAcquire("admin@example.test", "key-0")).isTrue();
        assertThat(limiter.tryAcquire("admin@example.test", "overflow-key")).isFalse();

        clock.advance(ContainerActionRateLimiter.WINDOW);

        assertThat(limiter.tryAcquire("admin@example.test", "new-window-key")).isTrue();
        assertThat(limiter.principalCount()).isOne();
    }

    @Test
    void should_consumeOneDistinctSlot_when_sameKeyIsAcquiredConcurrently() throws Exception {
        ContainerActionRateLimiter limiter = new ContainerActionRateLimiter(
                Clock.fixed(Instant.parse("2026-08-20T15:00:00Z"), ZoneId.of("UTC")));
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<Boolean>> acquisitions = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        return limiter.tryAcquire("admin@example.test", "same-key");
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Boolean> acquisition : acquisitions) {
                assertThat(acquisition.get(5, TimeUnit.SECONDS)).isTrue();
            }
        }

        for (int key = 1; key < ContainerActionRateLimiter.MAXIMUM_REQUESTS; key++) {
            assertThat(limiter.tryAcquire("admin@example.test", "distinct-" + key)).isTrue();
        }
        assertThat(limiter.tryAcquire("admin@example.test", "overflow-key")).isFalse();
    }

    @Test
    void should_failClosedAtBoundedPrincipalCapacity_when_allEntriesAreActive() {
        ContainerActionRateLimiter limiter = new ContainerActionRateLimiter(
                Clock.fixed(Instant.parse("2026-08-20T15:00:00Z"), ZoneId.of("UTC")));

        for (int principal = 0;
                principal < ContainerActionRateLimiter.MAXIMUM_PRINCIPALS;
                principal++) {
            assertThat(limiter.tryAcquire("admin-" + principal, "key")).isTrue();
        }

        assertThat(limiter.tryAcquire("overflow-admin", "key")).isFalse();
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
