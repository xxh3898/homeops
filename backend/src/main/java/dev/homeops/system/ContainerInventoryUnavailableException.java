package dev.homeops.system;

public class ContainerInventoryUnavailableException extends RuntimeException {

    public ContainerInventoryUnavailableException() {
        super("Container inventory is unavailable");
    }
}
