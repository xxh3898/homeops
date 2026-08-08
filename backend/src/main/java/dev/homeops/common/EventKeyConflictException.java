package dev.homeops.common;

public class EventKeyConflictException extends RuntimeException {
    public EventKeyConflictException(String eventKey) {
        super("Event key conflicts with an existing ingestion request: " + eventKey);
    }
}
