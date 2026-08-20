package dev.homeops.agent.control;

public final class ContainerControlResultRejectedException extends RuntimeException {

    public ContainerControlResultRejectedException() {
        super("Container control result is invalid");
    }
}
