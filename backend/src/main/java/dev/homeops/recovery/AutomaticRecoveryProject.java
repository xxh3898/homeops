package dev.homeops.recovery;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AutomaticRecoveryProject {
    RHAOMI("rhaomi");

    private final String wireValue;

    AutomaticRecoveryProject(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonCreator
    public static AutomaticRecoveryProject fromWireValue(String value) {
        for (AutomaticRecoveryProject project : values()) {
            if (project.wireValue.equals(value)) {
                return project;
            }
        }
        throw new IllegalArgumentException("Automatic recovery project is invalid");
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
