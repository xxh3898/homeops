package dev.homeops.activity;

public class InvalidActivityTypeException extends RuntimeException {
    public InvalidActivityTypeException() {
        super("Activity type is invalid");
    }
}
