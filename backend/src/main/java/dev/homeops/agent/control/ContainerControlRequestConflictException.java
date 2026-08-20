package dev.homeops.agent.control;

public final class ContainerControlRequestConflictException extends RuntimeException {

    public ContainerControlRequestConflictException() {
        super("A container control request is already active");
    }
}
