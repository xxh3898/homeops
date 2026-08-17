package dev.homeops.agent.logs;

public class InvalidContainerLogTailException extends RuntimeException {

    public InvalidContainerLogTailException() {
        super("Container log tail is invalid");
    }
}
