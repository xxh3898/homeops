package dev.homeops.agent.logs;

public class ContainerLogsNotAllowedException extends RuntimeException {

    public ContainerLogsNotAllowedException() {
        super("Container logs are not allowed");
    }
}
