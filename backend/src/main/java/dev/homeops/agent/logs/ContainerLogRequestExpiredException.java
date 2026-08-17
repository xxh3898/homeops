package dev.homeops.agent.logs;

public class ContainerLogRequestExpiredException extends RuntimeException {

    public ContainerLogRequestExpiredException() {
        super("Container log request expired");
    }
}
