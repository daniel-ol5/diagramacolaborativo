package com.modelcollab.metamodel.model;

import java.util.List;

/**
 * Raíz del modelo canónico (§7). Es la fuente única de verdad compartida por el lienzo
 * web, el backend, los validadores, el exportador XMI y los generadores de código.
 */
public record CanonicalModel(
        String schemaVersion,
        long mutationVersion,
        List<Package> packages,
        List<ClassEntity> classes,
        List<Relationship> relationships) {

    public static final String CURRENT_SCHEMA_VERSION = "1.0.0";

    public CanonicalModel {
        schemaVersion = schemaVersion == null ? CURRENT_SCHEMA_VERSION : schemaVersion;
        packages = packages == null ? List.of() : List.copyOf(packages);
        classes = classes == null ? List.of() : List.copyOf(classes);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
    }

    /** Modelo vacío usado al crear un diagrama nuevo. */
    public static CanonicalModel empty() {
        return new CanonicalModel(CURRENT_SCHEMA_VERSION, 1L, List.of(), List.of(), List.of());
    }
}
