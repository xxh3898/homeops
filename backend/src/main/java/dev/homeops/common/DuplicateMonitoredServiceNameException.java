package dev.homeops.common;

public class DuplicateMonitoredServiceNameException extends RuntimeException {
    public DuplicateMonitoredServiceNameException() {
        super("A monitored service with this name already exists");
    }
}
