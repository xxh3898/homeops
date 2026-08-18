package dev.homeops.agent.logs;

public class ContainerLogBrokerCapacityException extends RuntimeException {

    public ContainerLogBrokerCapacityException() {
        super("Container log request capacity is unavailable");
    }
}
