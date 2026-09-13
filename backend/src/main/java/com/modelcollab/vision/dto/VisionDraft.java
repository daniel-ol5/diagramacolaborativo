package com.modelcollab.vision.dto;

import java.util.List;

/**
 * Lo que el sistema cree haber leído en la foto. <b>Todavía no es un diagrama.</b>
 *
 * <h2>Por qué el borrador habla de nombres y no de identificadores ni de coordenadas</h2>
 *
 * <p>Por lo mismo que en CU16: la identidad y la disposición son del servidor. Una foto no trae
 * identificadores —no hay nada en una pizarra que sea un UUID— y tampoco trae coordenadas
 * utilizables: las de la cartulina no sirven en un lienzo, dependen del ángulo de la cámara y de
 * dónde estaba parado el que sacó la foto.
 *
 * <p>Así que el borrador describe <b>qué</b> hay: clases, sus atributos y qué se conecta con qué.
 * El <b>dónde</b> lo calcula la grilla y el <b>cuál</b> lo asigna el servidor al confirmar.
 *
 * <p>Este mismo registro viaja en las dos direcciones: el servidor lo propone y el navegador lo
 * devuelve, con las correcciones que la persona haya hecho en el modal. Eso significa que a la
 * vuelta <b>viene de un cliente</b>, y se valida entero otra vez — que lo haya propuesto el
 * servidor un segundo antes no lo vuelve confiable.
 */
public record VisionDraft(List<VisionClass> classes, List<VisionRelationship> relationships) {

    public VisionDraft {
        classes = classes == null ? List.of() : List.copyOf(classes);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
    }
}
