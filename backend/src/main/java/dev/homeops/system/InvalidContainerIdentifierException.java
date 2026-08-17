package dev.homeops.system;

public class InvalidContainerIdentifierException extends RuntimeException {

    public InvalidContainerIdentifierException() {
        super("Container identifier is invalid");
    }
}
