package dev.homeops.agent.control;

public final class ContainerControlRequestGoneException extends RuntimeException {

    public ContainerControlRequestGoneException() {
        super("Container control request is no longer available");
    }
}
