package dev.homeops.agent.logs;

public class ContainerLogCapabilityUnavailableException extends RuntimeException {

    public ContainerLogCapabilityUnavailableException() {
        super("Container log capability is unavailable");
    }
}
