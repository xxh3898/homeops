package dev.homeops.agent.logs;

public class ContainerLogRetrievalUnavailableException extends RuntimeException {

    public ContainerLogRetrievalUnavailableException() {
        super("Container log retrieval is unavailable");
    }
}
