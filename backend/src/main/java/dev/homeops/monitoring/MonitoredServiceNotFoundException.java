package dev.homeops.monitoring;

public class MonitoredServiceNotFoundException extends RuntimeException {

    public MonitoredServiceNotFoundException() {
        super("Monitored service does not exist");
    }
}
