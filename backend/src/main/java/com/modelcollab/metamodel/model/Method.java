package com.modelcollab.metamodel.model;

import java.util.List;

/** Método de una clase; alimenta la capa de servicio del generador de código. */
public record Method(
        String id,
        String name,
        String returnType,
        Visibility visibility,
        List<Parameter> parameters) {

    public Method {
        visibility = visibility == null ? Visibility.PUBLIC : visibility;
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
}
