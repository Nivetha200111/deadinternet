package dev.deadinternet.classification;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** Behavioral classification labels. These describe signals, never the identity behind an account. */
public enum Classification {
    HUMAN_LIKE("human_like"),
    UNCERTAIN("uncertain"),
    AUTOMATION_LIKE("automation_like");

    private final String wire;

    Classification(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static Classification fromWire(String value) {
        return Arrays.stream(values()).filter(c -> c.wire.equalsIgnoreCase(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown classification: " + value));
    }
}
