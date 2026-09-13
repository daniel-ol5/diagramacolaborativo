package com.modelcollab.vision.dto;

/**
 * Una línea entre dos recuadros de la pizarra.
 *
 * <p>{@code from} y {@code to} son <b>nombres de clase</b>, y pueden apuntar tanto a una clase del
 * mismo borrador como a una que ya está en el diagrama: una foto de una pizarra puede traer la
 * mitad de un modelo que se conecta con lo que ya había.
 */
public record VisionRelationship(
        String from,
        String to,
        String type,
        String sourceMultiplicity,
        String targetMultiplicity) {}
