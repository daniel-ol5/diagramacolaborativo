package com.modelcollab.vision.dto;

import java.util.List;

/** Una clase leída de la foto: su nombre y lo que se alcanzó a leer adentro del recuadro. */
public record VisionClass(String name, List<VisionAttribute> attributes) {

    public VisionClass {
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }
}
