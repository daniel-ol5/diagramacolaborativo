package com.modelcollab.vision.dto;

import java.util.List;

/**
 * La propuesta que se le muestra a la persona en el modal de revisión (RF-03.3).
 *
 * <p><b>Nada de esto se aplicó.</b> Este endpoint no sabe escribir: mira la foto y devuelve lo
 * que leyó. Es la mitad del guardarraíl de CU17, y la otra mitad es que el modal muestre de
 * verdad lo que hay acá dentro.
 *
 * <p>{@code simulated} dice si la lectura la hizo un modelo de verdad o el cliente simulado. Va
 * en la respuesta y no sólo en el log del servidor porque <b>la interfaz tiene que poder
 * decirlo</b>: sin clave, la propuesta no tiene ninguna relación con la foto que se subió, y
 * mostrarla sin avisar sería enseñar un reconocimiento que no ocurrió. Es la diferencia entre
 * una demostración y un truco.
 *
 * <p>{@code notes} son los avisos de lo que el servidor tocó por su cuenta — por ejemplo, que
 * renombró una clase porque ya existía una con ese nombre. Si no se dijeran, la persona
 * confirmaría algo distinto de lo que cree estar confirmando.
 */
public record VisionProposalResponse(
        boolean simulated,
        String message,
        VisionDraft draft,
        List<String> notes) {

    public VisionProposalResponse {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
