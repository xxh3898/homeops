package dev.homeops.common;

public class InvalidIngestionStateTransitionException extends RuntimeException {
    public InvalidIngestionStateTransitionException(String currentStatus, String requestedStatus) {
        super("Ingestion state transition is not allowed: " + currentStatus + " -> " + requestedStatus);
    }
}
