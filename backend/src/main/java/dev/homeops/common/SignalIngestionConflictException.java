package dev.homeops.common;

public class SignalIngestionConflictException extends RuntimeException {
    public SignalIngestionConflictException() {
        super("Signal episode conflicts with existing state");
    }
}
