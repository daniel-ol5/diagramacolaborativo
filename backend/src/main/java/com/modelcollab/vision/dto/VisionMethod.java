package com.modelcollab.vision.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Un método leído de la foto: su nombre y su tipo de retorno.
 */
public record VisionMethod(String name, String returnType) {

    @JsonCreator
    public VisionMethod(
            @JsonProperty("name") String name,
            @JsonProperty("returnType") String returnType) {
        this.name = name == null ? "" : name.strip();
        this.returnType = returnType == null || returnType.isBlank() ? "void" : returnType.strip();
    }
}

