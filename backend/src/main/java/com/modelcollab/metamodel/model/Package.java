package com.modelcollab.metamodel.model;

import java.util.List;

/** Agrupación lógica de clases; se traslada a paquetes Java y a XMI. */
public record Package(String id, String name, List<String> classIds) {

    public Package {
        classIds = classIds == null ? List.of() : List.copyOf(classIds);
    }
}
