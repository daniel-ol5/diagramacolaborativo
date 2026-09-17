package com.modelcollab.vision.dto;

/**
 * Lo que el teléfono recibe después de mandar la foto.
 *
 * <h2>El teléfono no ve la propuesta, y es a propósito</h2>
 *
 * <p>Manda bytes y recibe un acuse. No conoce el modelo canónico, no habla STOMP, no recibe
 * operaciones: la revisión ocurre en la pantalla grande, que es donde se puede leer un diagrama.
 * Si este DTO creciera hasta traer el borrador, el teléfono pasaría a ser un segundo cliente de
 * modelado y habría que mantenerlo como tal.
 *
 * <p>Igual trae <b>cuánto se leyó</b>, porque un "listo" a secas no distingue una foto que
 * produjo cinco clases de una que no produjo ninguna, y quien saca la foto está mirando el
 * teléfono, no la laptop.
 *
 * @param delivered si la propuesta llegó a una pantalla
 * @param reason código del motivo cuando no llegó, o {@code null}
 * @param message texto para mostrar en el teléfono, siempre presente
 * @param simulated si la lectura la hizo un modelo de verdad o el cliente simulado. Va acá por lo
 *     mismo que en las otras dos respuestas: <b>el simulado no disimula</b>
 * @param classes cuántas clases se leyeron
 * @param relationships cuántas relaciones se leyeron
 */
public record VisionCaptureResponse(
        boolean delivered,
        String reason,
        String message,
        boolean simulated,
        int classes,
        int relationships) {

    /**
     * Nadie tiene ese diagrama abierto.
     *
     * <p>No es un error: es la limitación honesta del diseño. No hay tabla ni almacenamiento
     * —una propuesta no aplicada no es un dato que valga la pena guardar, y guardarla obligaría a
     * decidir cuándo caduca y quién la borra—, así que sin una pantalla escuchando la foto no
     * tiene a dónde ir. Y es exactamente el uso real: laptop abierta, celular en la mano.
     */
    public static final String SIN_PANTALLA = "NO_OPEN_DIAGRAM";

    public static VisionCaptureResponse sinPantalla() {
        return new VisionCaptureResponse(
                false,
                SIN_PANTALLA,
                "Abrí este diagrama en la computadora y volvé a sacar la foto: la propuesta se "
                        + "revisa en la pantalla grande y ahora mismo no hay ninguna escuchando.",
                false,
                0,
                0);
    }

    public static VisionCaptureResponse entregada(VisionProposalResponse propuesta) {
        return new VisionCaptureResponse(
                true,
                null,
                "Listo: revisala en la computadora.",
                propuesta.simulated(),
                propuesta.draft().classes().size(),
                propuesta.draft().relationships().size());
    }
}
