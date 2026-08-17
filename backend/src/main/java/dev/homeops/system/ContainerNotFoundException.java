package dev.homeops.system;

public class ContainerNotFoundException extends RuntimeException {

    public ContainerNotFoundException() {
        super("Container is not present in the latest reported snapshot");
    }
}
