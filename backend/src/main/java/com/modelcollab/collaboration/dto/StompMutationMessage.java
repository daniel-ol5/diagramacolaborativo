package com.modelcollab.collaboration.dto;

import java.util.Map;

/**
 * Mensaje que un cliente envía a {@code /app/diagram/{diagramId}/mutate}. Contrato congelado
 * (D-11).
 *
 * <p>{@code op} es uno de los 22 del catálogo de §8.1. {@code targetId} es el elemento afectado
 * ({@code classId}, {@code attrId}, {@code relId}…) o {@code null} cuando la operación va contra
 * la raíz del diagrama ({@code ADD_CLASS}, {@code BULK_MERGE}).
 *
 * <p><b>Desvío D-11 sobre {@code payload}:</b> el diagrama de secuencia de §8.3 dibuja los
 * campos mutables sueltos en la raíz del mensaje ({@code name} al lado de {@code op}), pero eso
 * es un boceto de secuencia y no el schema. Acá van anidados bajo {@code payload} por dos
 * razones: el interceptor tiene que enrutar y autorizar sin conocer los 22 conjuntos de campos
 * posibles, y la columna {@code diagram_operations.payload} de §13 ya es un JSONB que espera
 * exactamente ese sub-objeto, así que persistir es copiar y no re-empaquetar.
 *
 * <p>{@code clientMutationId} lo genera el cliente y el servidor lo devuelve intacto en la
 * difusión: es lo que permite al emisor reconocer su propio eco y no repintar (§8.3, paso 9).
 */
public record StompMutationMessage(
        String op, String targetId, String clientMutationId, Map<String, Object> payload) {}
