package com.modelcollab.metamodel.model;

/**
 * Atributo de una clase. Los defaults de §7 (length 255, precision 10, scale 2,
 * isPrimaryKey false, isNullable true, isUnique false) se aplican en el constructor
 * compacto, de modo que un campo ausente en el JSON queda normalizado al leerlo.
 * Los tipos son envueltos (no primitivos) justamente para poder distinguir
 * "ausente" (null) de un valor explícito.
 */
public record Attribute(
        String id,
        String name,
        AttributeType type,
        Integer length,
        Integer precision,
        Integer scale,
        Visibility visibility,
        Boolean isPrimaryKey,
        Boolean isNullable,
        Boolean isUnique,
        String defaultValue) {

    public static final int DEFAULT_LENGTH = 255;
    public static final int DEFAULT_PRECISION = 10;
    public static final int DEFAULT_SCALE = 2;

    public Attribute {
        length = length == null ? DEFAULT_LENGTH : length;
        precision = precision == null ? DEFAULT_PRECISION : precision;
        scale = scale == null ? DEFAULT_SCALE : scale;
        visibility = visibility == null ? Visibility.PRIVATE : visibility;
        isPrimaryKey = isPrimaryKey != null && isPrimaryKey;
        isNullable = isNullable == null || isNullable;
        isUnique = isUnique != null && isUnique;
    }
}
