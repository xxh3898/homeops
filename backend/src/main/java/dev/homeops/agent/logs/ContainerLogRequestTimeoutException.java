package dev.homeops.agent.logs;

public class ContainerLogRequestTimeoutException extends RuntimeException {

    public ContainerLogRequestTimeoutException() {
        super("Container log request timed out");
    }
}
