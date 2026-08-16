package dev.homeops.metrics;

public class InvalidMetricHistoryPeriodException extends RuntimeException {
    public InvalidMetricHistoryPeriodException() {
        super("Metric history period is missing or unsupported");
    }
}
