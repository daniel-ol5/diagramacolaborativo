package com.modelcollab.vision.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.ai.dto.AiCommandResponse;
import com.modelcollab.ai.service.AiPlanParser;
import com.modelcollab.ai.service.RespuestaDeIaInvalidaException;
import com.modelcollab.vision.dto.VisionClass;
import com.modelcollab.vision.dto.VisionDraft;
import com.modelcollab.vision.dto.VisionRelationship;
import org.springframework.stereotype.Component;

/**
 * Valida la <b>forma</b> de lo que devolvió el modelo multimodal.
 *
 * <h2>Qué comprueba acá y qué se comprueba después</h2>
 *
 * <p>Esta clase se ocupa de la estructura: que sea JSON, que no traiga campos que el esquema no
 * define, que las clases tengan nombre y que la cantidad sea razonable. <b>No juzga los tipos de
 * atributo</b>, y eso es deliberado.
 *
 * <p>En CU16 un tipo inventado se rechaza en el acto. Acá no, porque el caso de uso es distinto:
 * RF-03.3 pone a una persona a revisar y corregir justamente los <i>"tipos mal detectados"</i>.
 * Tirar la foto entera porque el modelo leyó "decmal" en una pizarra escrita a mano haría la
 * función inservible. El tipo ilegible se convierte en {@code VARCHAR}, se anota en la propuesta
 * para que se vea, y la persona lo arregla en el modal.
 *
 * <p><b>La indulgencia vale sólo donde hay alguien mirando.</b> Cuando el borrador vuelve
 * confirmado, el tipo tiene que ser válido y no hay segunda oportunidad: en ese momento ya no es
 * una lectura para revisar, es una escritura sobre el modelo canónico.
 */
@Component
public class VisionDraftParser {

    /**
     * Tope de clases por foto.
     *
     * <p><b>No es el guardarraíl de RF-02.6</b> y conviene no confundirlos. Aquel existe para que
     * la IA no invente un dominio; éste existe para que una respuesta absurda no se lleve puesta
     * la memoria del servidor. Uno es una decisión de producto y el otro un límite de recursos:
     * por eso aquél es cinco y éste sesenta. Una pizarra con sesenta clases no existe, pero una
     * respuesta corrupta con sesenta mil sí es posible.
     */
    public static final int MAXIMO_DE_CLASES = 60;

    private final ObjectMapper objectMapper;

    public VisionDraftParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public VisionDraft parsear(String crudo) {
        if (crudo == null || crudo.isBlank()) {
            throw roto("El servicio de visión devolvió una respuesta vacía");
        }

        VisionDraft borrador;
        try {
            // Se comparte el destapado de bloques de código con CU16 a propósito: es la única
            // indulgencia que se le concede al texto de un modelo, y tiene que haber un solo
            // lugar donde afloje. Con dos copias, alguien relaja una y la otra queda distinta.
            borrador = objectMapper.readValue(
                    AiPlanParser.desenvolverBloqueDeCodigo(crudo), VisionDraft.class);
        } catch (JsonProcessingException e) {
            throw new RespuestaDeIaInvalidaException(
                    AiCommandResponse.CONTRATO_ROTO,
                    "El servicio de visión devolvió algo que no respeta el formato acordado: "
                            + e.getOriginalMessage(),
                    e);
        }
        if (borrador == null) {
            throw roto("El servicio de visión devolvió un JSON vacío");
        }
        if (borrador.classes().size() > MAXIMO_DE_CLASES) {
            throw roto("El servicio de visión devolvió " + borrador.classes().size()
                    + " clases y el máximo son " + MAXIMO_DE_CLASES);
        }

        for (int i = 0; i < borrador.classes().size(); i++) {
            VisionClass clase = borrador.classes().get(i);
            if (clase.name() == null || clase.name().isBlank()) {
                throw roto("La clase " + (i + 1) + " volvió sin nombre");
            }
        }
        for (int i = 0; i < borrador.relationships().size(); i++) {
            VisionRelationship relacion = borrador.relationships().get(i);
            if (relacion.from() == null || relacion.from().isBlank()
                    || relacion.to() == null || relacion.to().isBlank()) {
                throw roto("La relación " + (i + 1) + " no dice qué une con qué");
            }
        }
        return borrador;
    }

    private static RespuestaDeIaInvalidaException roto(String mensaje) {
        return new RespuestaDeIaInvalidaException(AiCommandResponse.CONTRATO_ROTO, mensaje);
    }
}
