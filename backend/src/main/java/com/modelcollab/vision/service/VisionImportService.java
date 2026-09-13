package com.modelcollab.vision.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.ai.dto.AiCommandResponse;
import com.modelcollab.ai.service.RespuestaDeIaInvalidaException;
import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.collaboration.dto.StompBroadcastMessage;
import com.modelcollab.collaboration.dto.StompMutationMessage;
import com.modelcollab.collaboration.service.DiagramMutationService;
import com.modelcollab.collaboration.service.SequenceService;
import com.modelcollab.diagram.dto.ValidationReportDTO;
import com.modelcollab.diagram.repository.DiagramOperationRepository;
import com.modelcollab.diagram.service.PreGenerationValidatorService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.MetamodelValidator;
import com.modelcollab.vision.dto.VisionConfirmRequest;
import com.modelcollab.vision.dto.VisionDraft;
import com.modelcollab.vision.dto.VisionProposalResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CU17: importar un diagrama desde una foto de pizarra, con revisión humana.
 *
 * <h2>El guardarraíl acá es de otra naturaleza que en CU16</h2>
 *
 * <p>En CU16 el guardarraíl es <b>automático</b>: entre lo que el usuario dicta y lo que se
 * aplica no media nadie, así que el sistema tiene que decidir solo, y por eso hay un tope duro de
 * cinco operaciones.
 *
 * <p>Acá el guardarraíl es <b>humano</b>. RF-03.3 exige revisión: la persona ve la propuesta, la
 * corrige y la confirma. Por eso <b>no hay tope</b> — una pizarra con doce clases es un uso
 * legítimo, no un desborde — y el tope se reemplaza por la confirmación explícita.
 *
 * <p>Eso vale <b>sólo si la confirmación es real</b>. Un "¿aceptar?" sobre algo que no se ve es
 * un <i>human-in-the-loop</i> de mentira, y la garantía estructural de que no lo sea está en la
 * forma de este servicio: {@link #analizar} <b>no sabe escribir</b>. No recibe el servicio de
 * mutación, no toca la base, no difunde nada. Lo único que puede hacer es devolver lo que leyó.
 * Que el modal muestre eso es la otra mitad, y es del lado del navegador.
 *
 * <h2>Por qué se puede reemplazar el diagrama sin perder nada</h2>
 *
 * <p>{@code BULK_MERGE} reemplaza el estado por el modelo que lleva en el payload — así está
 * definido en §8.1 y así lo necesita la importación XMI. Acá el payload es <b>todo lo que había
 * más lo nuevo</b>, calculado por {@link VisionMerger}, que sólo agrega. La operación sigue
 * siendo un reemplazo y el resultado es una inserción.
 *
 * <p>Eso deja una carrera que hay que cerrar a mano y que es la parte delicada de esta clase: el
 * payload se calcula leyendo el estado, así que entre esa lectura y la aplicación no puede
 * entrar la mutación de nadie. Ver {@link SequenceService#tomarElDiagrama}.
 */
@Service
public class VisionImportService {

    private static final Logger log = LoggerFactory.getLogger(VisionImportService.class);

    /** Lo que hay que difundir a la sala una vez commiteado. */
    public record Resultado(
            StompBroadcastMessage difusion,
            int clasesNuevas,
            int relacionesNuevas,
            long mutationVersion) {}

    private final VisionModelClient cliente;
    private final VisionImageValidator validadorDeImagen;
    private final VisionDraftParser parser;
    private final VisionMerger fusionador;
    private final MetamodelValidator validador;
    private final PreGenerationValidatorService estados;
    private final SequenceService sequenceService;
    private final DiagramMutationService mutationService;
    private final DiagramOperationRepository operationRepository;
    private final ObjectMapper objectMapper;

    public VisionImportService(
            VisionModelClient cliente,
            VisionImageValidator validadorDeImagen,
            VisionDraftParser parser,
            VisionMerger fusionador,
            MetamodelValidator validador,
            PreGenerationValidatorService estados,
            SequenceService sequenceService,
            DiagramMutationService mutationService,
            DiagramOperationRepository operationRepository,
            ObjectMapper objectMapper) {
        this.cliente = cliente;
        this.validadorDeImagen = validadorDeImagen;
        this.parser = parser;
        this.fusionador = fusionador;
        this.validador = validador;
        this.estados = estados;
        this.sequenceService = sequenceService;
        this.mutationService = mutationService;
        this.operationRepository = operationRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Mira la foto y devuelve lo que leyó. <b>No aplica nada.</b>
     *
     * <p>La imagen se procesa en memoria y se descarta al salir del método: no se guarda en
     * disco, no va a ningún bucket, no queda en la base. Son fotos de pizarras ajenas sacadas en
     * una oficina — lo que no se guarda no se filtra, y no tenemos ningún motivo para tenerlas.
     */
    @Transactional(readOnly = true)
    public VisionProposalResponse analizar(UUID diagramId, byte[] imagen) {
        String tipoMime = validadorDeImagen.tipoDe(imagen);

        String crudo;
        try {
            crudo = cliente.leer(imagen, tipoMime);
        } catch (RespuestaDeIaInvalidaException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("El servicio de visión ({}) no respondió: {}",
                    cliente.descripcion(), e.toString());
            throw new RespuestaDeIaInvalidaException(
                    AiCommandResponse.IA_CAIDA,
                    "El servicio de visión no está disponible en este momento. "
                            + "El diagrama no se tocó.",
                    e);
        }

        VisionDraft leido = parser.parsear(crudo);
        CanonicalModel estado = estados.estadoDe(diagramId);

        // Blando: lo que no se entiende se anota y la persona lo arregla en el modal.
        VisionMerger.Resultado propuesta = fusionador.fusionar(estado, leido, false);

        log.debug("Foto leída para {}: {} clase(s), {} relación(es), {} aviso(s)",
                diagramId, propuesta.clasesNuevas(), propuesta.relacionesNuevas(),
                propuesta.notas().size());

        return new VisionProposalResponse(
                cliente.esSimulado(),
                cliente.esSimulado()
                        ? FakeVisionModelClient.aviso()
                        : "Revisá lo que leí antes de confirmar: los nombres y los tipos de una "
                                + "pizarra escrita a mano se malinterpretan seguido.",
                propuesta.borradorSaneado(),
                propuesta.notas());
    }

    /**
     * Aplica lo que la persona confirmó, en <b>un solo evento</b> para toda la sala.
     *
     * <p>No se reemiten N operaciones: veinte parpadeos en el lienzo de cada colaborador mientras
     * entra un diagrama importado sería inaceptable, y además dejaría al resto viendo estados
     * intermedios que nunca existieron como diagrama completo.
     */
    @Transactional
    public Resultado confirmar(UUID diagramId, UUID userId, VisionConfirmRequest peticion) {

        String mutationId = peticion.clientMutationId() == null
                || peticion.clientMutationId().isBlank()
                ? "vision-" + UUID.randomUUID()
                : peticion.clientMutationId().strip();

        Optional<com.modelcollab.diagram.model.DiagramOperation> yaAplicada =
                operationRepository.findByDiagramIdAndClientMutationId(diagramId, mutationId);
        if (yaAplicada.isPresent()) {
            return new Resultado(null, 0, 0, yaAplicada.get().getSequenceNum());
        }

        // ORDEN CRÍTICO. Esto toma el lock de la fila del diagrama sin consumir número, y se
        // sostiene hasta el commit. La lectura de abajo tiene que ocurrir DESPUÉS: el payload de
        // un BULK_MERGE es el modelo entero, así que una mutación ajena que se cuele entre la
        // lectura y la aplicación desaparecería sin dejar rastro — no como un conflicto que
        // alguien pueda ver, sino como trabajo borrado en silencio.
        sequenceService.tomarElDiagrama(diagramId);
        CanonicalModel estado = estados.estadoDe(diagramId);

        // Duro: ya hubo alguien mirando, y lo que llega ahora viene de un navegador.
        VisionMerger.Resultado fusion =
                fusionador.fusionar(estado, peticion.draft(), true);

        List<String> empeoraria = erroresNuevos(estado, fusion.modelo());
        if (!empeoraria.isEmpty()) {
            throw new VisionMerger.BorradorInvalidoException(
                    "No lo apliqué porque dejaría el diagrama sin poder generarse: "
                            + String.join(" ", empeoraria));
        }

        DiagramMutationService.Resultado resultado = mutationService.aplicar(
                diagramId, userId,
                new StompMutationMessage(
                        OperationType.BULK_MERGE.name(), null, mutationId,
                        objectMapper.convertValue(
                                fusion.modelo(), new TypeReference<Map<String, Object>>() {})));

        if (resultado instanceof DiagramMutationService.Resultado.Aplicada aplicada) {
            log.debug("Foto confirmada en {}: +{} clase(s), +{} relación(es)",
                    diagramId, fusion.clasesNuevas(), fusion.relacionesNuevas());
            return new Resultado(aplicada.difusion(), fusion.clasesNuevas(),
                    fusion.relacionesNuevas(), aplicada.difusion().sequenceNum());
        }
        // Duplicada: alguien confirmó dos veces y la segunda no cambió nada.
        return new Resultado(null, 0, 0,
                ((DiagramMutationService.Resultado.Duplicada) resultado).sequenceNumOriginal());
    }

    /**
     * Los errores bloqueantes que tendría el diagrama después y no tenía antes.
     *
     * <p>Mismo criterio que en CU16: un diagrama que ya venía roto no es culpa de esta foto, y
     * bloquearlo para siempre por algo que alguien dejó mal antes sería peor que el problema.
     */
    private List<String> erroresNuevos(CanonicalModel antes, CanonicalModel despues) {
        Set<String> yaEstaban = new HashSet<>();
        validador.validar(antes).errors()
                .forEach(e -> yaEstaban.add(e.code() + "|" + e.elementId()));

        return validador.validar(despues).errors().stream()
                .filter(e -> !yaEstaban.contains(e.code() + "|" + e.elementId()))
                .map(ValidationReportDTO.ValidationIssue::message)
                .toList();
    }
}
