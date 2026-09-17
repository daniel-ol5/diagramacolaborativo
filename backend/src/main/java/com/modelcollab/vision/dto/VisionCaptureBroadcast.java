package com.modelcollab.vision.dto;

import java.util.UUID;

/**
 * La propuesta empujada al navegador después de una foto sacada desde el celular.
 *
 * <h2>Va al usuario, no a la sala — y la diferencia no es de estilo</h2>
 *
 * <p>Se manda por {@code /user/queue/vision}, el canal privado de quien sacó la foto, y
 * <b>no</b> por {@code /topic/diagrams/{id}}. Si fuera al topic, el modal de revisión se abriría
 * en la pantalla de <b>todos los colaboradores de la sala a la vez</b>; y como revisar termina en
 * confirmar, y confirmar aplica operaciones al modelo compartido, tres personas podrían confirmar
 * la misma propuesta y el diagrama quedaría con tres copias de cada clase.
 *
 * <p>La propuesta es de quien apuntó el teléfono a la pizarra. De nadie más.
 *
 * <h2>No es una mutación, y la forma lo dice</h2>
 *
 * <p>No lleva {@code op}, ni {@code clientMutationId}, ni {@code sequenceNum}. Los tres tienen un
 * motivo:
 *
 * <ul>
 *   <li>Sin {@code op}, el cliente no lo confunde con un cambio del diagrama — su
 *       {@code esBroadcastDeMutacion} se decide justamente por ese campo.</li>
 *   <li>Sin {@code clientMutationId}, no pasa por el filtrado de ecos. Ese filtro existe para
 *       descartar <i>mi propia mutación que vuelve</i>, y esto no es la mutación de nadie:
 *       <b>todavía no se aplicó nada</b>.</li>
 *   <li>Sin {@code sequenceNum} porque no se consumió ninguno. Gastar un número de secuencia sin
 *       mutar le haría creer al cliente que se perdió un mensaje.</li>
 * </ul>
 *
 * <h2>Por qué viaja el {@code diagramId} si el canal ya es privado</h2>
 *
 * <p>Porque una cola de usuario es <b>por usuario, no por diagrama</b>. Con dos pestañas abiertas
 * en dos diagramas distintos, este mensaje llega a las dos, y sin este campo la pestaña
 * equivocada abriría el modal y la persona confirmaría clases en un proyecto que no era.
 *
 * @param event siempre {@code VISION_PROPOSAL}
 * @param source de dónde vino la foto; hoy sólo {@code MOBILE}
 * @param diagramId a qué diagrama pertenece: el cliente <b>tiene</b> que compararlo con el suyo
 * @param proposal exactamente lo que devuelve {@code POST .../vision-import}, para que el modal
 *     de revisión se abra sin adaptar nada
 */
public record VisionCaptureBroadcast(
        String event, String source, UUID diagramId, VisionProposalResponse proposal) {

    public static final String EVENTO = "VISION_PROPOSAL";
    public static final String DESDE_EL_CELULAR = "MOBILE";

    public static VisionCaptureBroadcast delCelular(
            UUID diagramId, VisionProposalResponse propuesta) {
        return new VisionCaptureBroadcast(EVENTO, DESDE_EL_CELULAR, diagramId, propuesta);
    }
}
