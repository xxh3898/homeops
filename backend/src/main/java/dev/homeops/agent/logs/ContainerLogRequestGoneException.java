package dev.homeops.agent.logs;

public class ContainerLogRequestGoneException extends RuntimeException {

    public ContainerLogRequestGoneException() {
        super("Container log request is no longer available");
    }
}
