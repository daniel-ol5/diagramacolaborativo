package com.modelcollab.vision.dto;

/**
 * Un atributo leído de la foto.
 *
 * <p>{@code type} viaja como texto y no como el enumerado del metamodelo a propósito: lo escribió
 * un modelo mirando una pizarra, así que puede ser cualquier cosa. Convertirlo —y rechazarlo si
 * no existe— es trabajo del validador, no del transporte. Si el tipo fuera el enumerado, un valor
 * inventado reventaría durante la deserialización y el mensaje de error hablaría de JSON en vez
 * de decir qué tipo no existe.
 */
public record VisionAttribute(String name, String type) {}
