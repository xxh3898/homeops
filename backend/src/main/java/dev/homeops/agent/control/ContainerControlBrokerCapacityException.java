package dev.homeops.agent.control;

public final class ContainerControlBrokerCapacityException extends RuntimeException {

    public ContainerControlBrokerCapacityException() {
        super("Container control request capacity is busy");
    }
}
