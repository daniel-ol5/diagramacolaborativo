package com.modelcollab.metamodel.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Cardinalidad UML. Los literales del schema ("0..1", "1..1", "0..*", "1..*") no son
 * identificadores Java válidos, por lo que la constante y su representación JSON se
 * desacoplan con {@link JsonValue} / {@link JsonCreator}.
 */
public enum Multiplicity {
    ZERO_ONE("0..1"),
    ONE_ONE("1..1"),
    ZERO_MANY("0..*"),
    ONE_MANY("1..*");

    private final String value;

    Multiplicity(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static Multiplicity fromValue(String value) {
        for (Multiplicity m : values()) {
            if (m.value.equals(value)) {
                return m;
            }
        }
        throw new IllegalArgumentException("Multiplicidad no soportada: " + value);
    }

    /** true si el extremo admite más de una instancia ("0..*" o "1..*"). */
    public boolean isCollection() {
        return this == ZERO_MANY || this == ONE_MANY;
    }
}
