package com.modelcollab.diagram.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * Una operación que el cliente encoló en IndexedDB mientras estaba sin conexión.
 *
 * <p>Misma forma que el mensaje STOMP de mutación, más {@code clientTimestamp}. Ese campo
 * <b>no</b> es autoritativo: §9.2 punto 1 es explícito en que el timestamp oficial lo pone el
 * servidor al recibir, y el del cliente sólo sirve para preservar el orden relativo original de
 * la cola. Un colaborador con el reloj adelantado no puede reordenar la historia de los demás.
 *
 * <p>{@code clientMutationId} es obligatorio acá, al revés que en el mensaje STOMP: es lo único
 * que hace seguro reintentar un lote entero. Sin él, un cliente que pierde la respuesta y
 * reenvía duplicaría cada operación.
 */
public record OfflineOperation(
        @NotBlank String op,
        String targetId,
        @NotBlank String clientMutationId,
        Map<String, Object> payload,
        Long clientTimestamp) {}
