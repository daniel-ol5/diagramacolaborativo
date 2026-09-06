package com.modelcollab.collaboration.dto;

import java.util.UUID;

/**
 * Quién es un colaborador a los ojos de la sala. Se arma una vez en el CONNECT y viaja en los
 * atributos de la sesión WebSocket: los locks y la presencia lo necesitan en cada mensaje, y
 * volver a la base por el nombre en cada uno sería el mismo error que cachear el rol resolvió.
 *
 * <p>El campo se llama {@code userName} y no {@code nombre} (desvío <b>D-24</b>) porque
 * <b>todo el contrato de cable está en inglés</b>: §7 usa {@code schemaVersion} e
 * {@code isAbstract}, D-11 usa {@code op} y {@code clientMutationId}, y las difusiones de cursor
 * y de lock ya traían {@code userName} para este mismo dato. Un único campo en español entre
 * veinte en inglés es el que produce el {@code participante.userName} a las dos de la mañana, y
 * es un error que el compilador de TypeScript no puede prevenir del otro lado del cable. El
 * español se queda donde sirve: comentarios, mensajes al usuario y documentación.
 */
public record Participante(UUID userId, String userName, String color) {

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

    public static Participante de(UUID userId, String userName) {
        int indice = Math.floorMod(userId.hashCode(), PALETA.length);
        return new Participante(userId, userName, PALETA[indice]);
    }
}
