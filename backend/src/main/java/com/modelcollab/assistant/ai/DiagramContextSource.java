package com.modelcollab.assistant.ai;

import com.modelcollab.metamodel.model.CanonicalModel;
import java.util.Optional;
import java.util.UUID;

/**
 * De dónde saca la IA del chat el estado canónico del diagrama del mensaje (CU20). Solo lectura.
 *
 * <p>Nunca lanza: si el diagrama no existe, es de otro proyecto o no se puede leer, devuelve
 * {@link Optional#empty()} y la IA responde sin resumen del diagrama.
 */
public interface DiagramContextSource {

    Optional<CanonicalModel> cargar(UUID diagramId, UUID projectId);

    /** Sin diagramas: para tests y para quien no quiera contexto. */
    static DiagramContextSource ninguno() {
        return (diagrama, proyecto) -> Optional.empty();
    }
}
