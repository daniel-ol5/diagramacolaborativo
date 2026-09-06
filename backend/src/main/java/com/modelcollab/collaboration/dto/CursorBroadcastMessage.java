package com.modelcollab.collaboration.dto;

import java.util.UUID;

/**
 * Cursor de un colaborador, difundido a la sala por {@code /topic/diagrams/{diagramId}}.
 *
 * <p>Viaja con {@code userName} y {@code color} ya resueltos, no sólo con el {@code userId}: el
 * cliente no debería tener que cruzar cada cursor contra una tabla de participantes para poder
 * dibujarlo, y el color es el mismo que el de {@code LOCK_ACQUIRED} para esa persona — así el
 * candado de una clase y el puntero de quien la está editando se ven del mismo color.
 *
 * <p><b>Efímero por definición:</b> no se persiste, no consume secuencia y el siguiente lo
 * reemplaza. Perder uno no tiene consecuencias, que es justo lo que permite mandarlos a la
 * frecuencia del movimiento del mouse.
 */
public record CursorBroadcastMessage(
        String event,
        UUID userId,
        String userName,
        String color,
        double x,
        double y,
        String selectedId) {

    public static final String USER_CURSOR = "USER_CURSOR";

    public static CursorBroadcastMessage de(Participante quien, CursorMessage cursor) {
        return new CursorBroadcastMessage(
                USER_CURSOR,
                quien.userId(),
                quien.nombre(),
                quien.color(),
                cursor.x(),
                cursor.y(),
                cursor.selectedId());
    }
}
