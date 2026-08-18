package dev.homeops.agent.logs;

public class ContainerLogRequestCancelledException extends RuntimeException {

    public ContainerLogRequestCancelledException() {
        super("Container log request was cancelled");
    }
}
