package com.modelcollab.generator.dto;

import java.util.List;

/**
 * El {@code intents.json} que se embarca en la app móvil.
 *
 * <p>{@code language} es "es" a secas y <b>no un dialecto</b>. El spike de la semana 1 encontró
 * que el listado de idiomas del reconocedor incluye los que no tienen modelo descargado, así que
 * fijar acá "es-ES" o "es-US" sería elegir por el aparato equivocado: la app tiene que mirar qué
 * modelo está instalado y quedarse con ése. Poner el dialecto en el catálogo lo congelaría para
 * todos los teléfonos.
 */
public record IntentCatalog(String schemaVersion, String language, List<Intent> intents) {

    public static final String VERSION = "1.0.0";

    public IntentCatalog {
        schemaVersion = schemaVersion == null ? VERSION : schemaVersion;
        language = language == null ? "es" : language;
        intents = intents == null ? List.of() : List.copyOf(intents);
    }
}
