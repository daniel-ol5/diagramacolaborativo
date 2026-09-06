package com.modelcollab.collaboration.dto;

import java.util.UUID;

/**
 * Quién es un colaborador a los ojos de la sala. Se arma una vez en el CONNECT y viaja en los
 * atributos de la sesión WebSocket: los locks y la presencia lo necesitan en cada mensaje, y
 * volver a la base por el nombre en cada uno sería el mismo error que cachear el rol resolvió.
 */
public record Participante(UUID userId, String nombre, String color) {

    /**
     * Paleta fija de colores bien separados en tono. El color se deriva del {@code userId} y no
     * se reparte por orden de llegada: así el mismo usuario tiene el mismo color en todas las
     * pestañas, en todas las salas y después de reconectar. Con asignación por orden, quien se
     * reconecta cambia de color y los demás ven "otra persona" editando.
     */
    private static final String[] PALETA = {
        "#e6194b", "#3cb44b", "#4363d8", "#f58231", "#911eb4",
        "#42d4f4", "#f032e6", "#bfef45", "#469990", "#9a6324"
    };

    public static Participante de(UUID userId, String nombre) {
        int indice = Math.floorMod(userId.hashCode(), PALETA.length);
        return new Participante(userId, nombre, PALETA[indice]);
    }
}
