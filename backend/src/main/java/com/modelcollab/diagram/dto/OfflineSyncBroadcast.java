package com.modelcollab.diagram.dto;

import com.modelcollab.metamodel.model.CanonicalModel;
import java.time.Instant;

/**
 * Lo que la sala recibe cuando un compañero termina de reconciliar su trabajo offline.
 *
 * <p><b>Un solo evento con el estado completo, no N mutaciones.</b> Reemitir las diez
 * operaciones de la cola una por una haría parpadear diez veces el lienzo de todos, y además los
 * dejaría pasando por estados intermedios que nunca existieron para nadie. Es el mismo criterio
 * que §8.1 fija para {@code BULK_MERGE}: una inserción atómica se difunde como un único evento.
 *
 * <p>El cliente reemplaza su estado por {@code snapshot} y listo; no tiene que reproducir nada.
 */
public record OfflineSyncBroadcast(
        String event,
        String userId,
        long sequenceNum,
        int appliedCount,
        Instant serverTimestamp,
        CanonicalModel snapshot) {

    public static final String OFFLINE_SYNC = "OFFLINE_SYNC";

    public static OfflineSyncBroadcast de(String userId, ReconciliationReport reporte) {
        return new OfflineSyncBroadcast(
                OFFLINE_SYNC,
                userId,
                reporte.snapshot().mutationVersion(),
                reporte.appliedCount(),
                Instant.now(),
                reporte.snapshot());
    }
}
