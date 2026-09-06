package com.modelcollab.collaboration.dto;

/**
 * Lo que un cliente envía a {@code /app/diagram/{diagramId}/cursor}: dónde tiene el puntero y
 * qué elemento está señalando.
 *
 * <p>Destino propio y no {@code /mutate} a propósito. Un cursor <b>no es una mutación</b>: no
 * toca el estado canónico, no se persiste y no consume número de secuencia. Hacerlo pasar por la
 * tubería de mutaciones obligaría a poner excepciones dentro de ella —"salvo si es un cursor"— en
 * cada uno de esos tres puntos, y bastaría con olvidar una para llenar {@code diagram_operations}
 * de basura a veinte mensajes por segundo por usuario. Es más barato que no entre nunca.
 *
 * <p>{@code selectedId} es opcional: el elemento que el usuario tiene seleccionado, para que los
 * demás vean qué está mirando aunque todavía no lo esté editando.
 */
public record CursorMessage(double x, double y, String selectedId) {}
