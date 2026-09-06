package com.modelcollab.diagram.dto;

import com.modelcollab.metamodel.model.CanonicalModel;
import java.util.List;

/**
 * Resultado del rebase, con la forma exacta del ejemplo de §9.2. La interfaz lo muestra en un
 * diálogo al reconectar.
 *
 * <p>{@code discardedDetails} son frases para leer, no códigos: <i>"ADD_ATTRIBUTE 'telefono'
 * descartado: la clase 'Cliente' fue eliminada por otro colaborador"</i>. Nombrar los elementos
 * por UUID sería inútil — a nadie le dice nada que se haya descartado la operación
 * {@code c7f3a1b2}. Como para cuando se arma el mensaje el elemento ya no existe en el estado
 * del servidor, los nombres se resuelven contra la bitácora de operaciones.
 *
 * <p>{@code snapshot} es el estado canónico ya reconciliado. Va en la misma respuesta a
 * propósito: el cliente acaba de descubrir que parte de su trabajo se descartó, y lo peor que se
 * le puede pedir en ese momento es una segunda petición para saber cómo quedó el diagrama.
 */
public record ReconciliationReport(
        int appliedCount,
        int discardedCount,
        List<String> discardedDetails,
        CanonicalModel snapshot) {}
