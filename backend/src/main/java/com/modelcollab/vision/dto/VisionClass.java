package com.modelcollab.vision.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Una clase leída de la foto: su nombre, atributos y métodos detectados. */
public record VisionClass(
        String name,
        List<VisionAttribute> attributes,
        List<VisionMethod> methods) {

    @JsonCreator
    public VisionClass(
            @JsonProperty("name") String name,
            @JsonProperty("attributes") List<VisionAttribute> attributes,
            @JsonProperty("methods") List<VisionMethod> methods) {
        this.name = name;
        this.attributes = attributes == null ? List.of() : List.copyOf(attributes);
        this.methods = methods == null ? List.of() : List.copyOf(methods);
    }

    public VisionClass(String name, List<VisionAttribute> attributes) {
        this(name, attributes, List.of());
    }
}
