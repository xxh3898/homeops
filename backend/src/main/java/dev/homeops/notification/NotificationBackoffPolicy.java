package dev.homeops.notification;

import dev.homeops.notification.config.HomeOpsNotificationProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class NotificationBackoffPolicy {
    private final Duration initialBackoff;
    private final Duration maximumBackoff;
    private final DoubleSupplier jitter;

    @Autowired
    NotificationBackoffPolicy(HomeOpsNotificationProperties properties) {
        this(properties.initialBackoff(), properties.maxBackoff(), Math::random);
    }

    NotificationBackoffPolicy(
            Duration initialBackoff,
            Duration maximumBackoff,
            DoubleSupplier jitter) {
        this.initialBackoff = initialBackoff;
        this.maximumBackoff = maximumBackoff;
        this.jitter = jitter;
    }

    Duration delay(int attemptCount, Optional<Duration> serverMinimum) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 20));
        long multiplier = 1L << exponent;
        Duration exponential;
        try {
            exponential = initialBackoff.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            exponential = maximumBackoff;
        }
        if (exponential.compareTo(maximumBackoff) > 0) {
            exponential = maximumBackoff;
        }
        double sample = Math.max(0.0, Math.min(1.0, jitter.getAsDouble()));
        long jitterNanos = (long) (exponential.toNanos() * 0.2 * sample);
        Duration computed;
        try {
            computed = exponential.plusNanos(jitterNanos);
        } catch (ArithmeticException exception) {
            computed = maximumBackoff;
        }
        if (computed.compareTo(maximumBackoff) > 0) {
            computed = maximumBackoff;
        }
        if (serverMinimum.isPresent() && serverMinimum.orElseThrow().compareTo(computed) > 0) {
            return serverMinimum.orElseThrow();
        }
        return computed;
    }
}
