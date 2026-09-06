package com.modelcollab.metamodel.model;

import java.util.List;

/**
 * Clase UML del diagrama. Defaults de §7: width 240, height 180, isAbstract false,
 * visibility PUBLIC.
 *
 * <p>El default de {@code visibility} lo declara §7 desde la enmienda <b>E-01</b>. Antes el
 * schema no lo definía y este constructor lo completaba igual, así que era una decisión del
 * backend y no del contrato: el Java rellenaba PUBLIC y un cliente TypeScript dejaba el campo
 * ausente, dos capas interpretando distinto el mismo dato faltante sin que ninguna fallara.
 *
 * <p>La asimetría con {@code Attribute.visibility} y {@code Method.visibility} —que siguen sin
 * default y son obligatorios— es deliberada: esas dos son portantes para el generador, porque
 * deciden si un campo sale {@code private} o un método {@code public}. La de la clase no lo es,
 * porque una entidad JPA generada es {@code public} siempre.
 *
 * <p>"class" es palabra reservada en Java, de ahí el nombre ClassEntity (§6).
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
