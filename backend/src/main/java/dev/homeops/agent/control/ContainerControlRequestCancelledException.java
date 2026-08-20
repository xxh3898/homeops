package dev.homeops.agent.control;

public final class ContainerControlRequestCancelledException extends RuntimeException {

    public ContainerControlRequestCancelledException() {
        super("Container control request was cancelled");
    }
}
