package com.modelcollab.diagram.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Cuerpo de {@code POST /api/v1/diagrams/{id}/sync-offline}: la cola completa de operaciones que
 * el cliente acumuló sin conexión, <b>en el orden en que las hizo</b>.
 *
 * <p>El orden es parte del dato y no un detalle de transporte: {@code MOVE_CLASS} es idempotente
 * y gana el último (§9.2), así que aplicarlas desordenadas dejaría la clase donde no estaba.
 *
 * <p>{@code baseSequenceNum} es la última secuencia que el cliente llegó a ver antes de
 * desconectarse. Hoy sólo se informa en el log; a partir de él se sabe exactamente qué cambios
 * ajenos ocurrieron durante la desconexión, que es lo que va a necesitar el diálogo de
 * reconciliación para explicar mejor los descartes.
 */
public record OfflineSyncRequest(
        Long baseSequenceNum,
        // El tope evita que un cliente con la cola corrupta mande cien mil operaciones y bloquee
        // la fila del diagrama para toda la sala durante la transacción.
        @NotEmpty @Size(max = 500) @Valid List<OfflineOperation> operations) {}
