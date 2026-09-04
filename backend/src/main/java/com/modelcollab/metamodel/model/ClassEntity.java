package com.modelcollab.metamodel.model;

import java.util.List;

/**
 * Clase UML del diagrama. Defaults de §7: width 240, height 180, isAbstract false.
 * "class" es palabra reservada en Java, de ahí el nombre ClassEntity (§6).
 */
public record ClassEntity(
        String id,
        String name,
        Visibility visibility,
        Boolean isAbstract,
        Position position,
        Double width,
        Double height,
        List<Attribute> attributes,
        List<Method> methods) {

    public static final double DEFAULT_WIDTH = 240d;
    public static final double DEFAULT_HEIGHT = 180d;

    public ClassEntity {
        visibility = visibility == null ? Visibility.PUBLIC : visibility;
        isAbstract = isAbstract != null && isAbstract;
        width = width == null ? DEFAULT_WIDTH : width;
        height = height == null ? DEFAULT_HEIGHT : height;
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
        methods = methods == null ? List.of() : List.copyOf(methods);
    }
}
