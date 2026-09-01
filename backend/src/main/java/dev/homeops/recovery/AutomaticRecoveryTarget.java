package dev.homeops.recovery;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AutomaticRecoveryTarget {
    RHAOMI_WEB("rhaomi-web"),
    BACKEND("backend");

    private final String wireValue;

    AutomaticRecoveryTarget(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonCreator
    public static AutomaticRecoveryTarget fromWireValue(String value) {
        for (AutomaticRecoveryTarget target : values()) {
            if (target.wireValue.equals(value)) {
                return target;
            }
        }
        throw new IllegalArgumentException("Automatic recovery target is invalid");
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
