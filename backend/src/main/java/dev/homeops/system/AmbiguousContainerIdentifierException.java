package dev.homeops.system;

public class AmbiguousContainerIdentifierException extends RuntimeException {

    public AmbiguousContainerIdentifierException() {
        super("Container identifier matches multiple reported containers");
    }
}
