package dev.homeops.agent.logs;

public class ContainerLogRequestConflictException extends RuntimeException {

    public ContainerLogRequestConflictException() {
        super("A container log request is already active");
    }
}
