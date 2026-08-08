package dev.homeops.activity;

public class InvalidActivityCursorException extends RuntimeException {
    public InvalidActivityCursorException() {
        super("Activity cursor is invalid");
    }
}
