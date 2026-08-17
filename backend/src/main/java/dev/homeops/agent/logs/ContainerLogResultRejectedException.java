package dev.homeops.agent.logs;

public class ContainerLogResultRejectedException extends RuntimeException {

    public ContainerLogResultRejectedException() {
        super("Container log result is invalid");
    }
}
