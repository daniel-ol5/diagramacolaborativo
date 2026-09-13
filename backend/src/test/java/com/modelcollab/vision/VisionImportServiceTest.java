package com.modelcollab.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.ai.service.GridLayoutEngine;
import com.modelcollab.ai.service.RespuestaDeIaInvalidaException;
import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.collaboration.dto.StompBroadcastMessage;
import com.modelcollab.collaboration.dto.StompMutationMessage;
import com.modelcollab.collaboration.service.DiagramMutationService;
import com.modelcollab.collaboration.service.SequenceService;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.diagram.repository.DiagramOperationRepository;
import com.modelcollab.diagram.service.PreGenerationValidatorService;
import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import com.modelcollab.metamodel.service.MetamodelValidator;
import com.modelcollab.vision.dto.VisionClass;
import com.modelcollab.vision.dto.VisionConfirmRequest;
import com.modelcollab.vision.dto.VisionDraft;
import com.modelcollab.vision.dto.VisionProposalResponse;
import com.modelcollab.vision.dto.VisionRelationship;
import com.modelcollab.vision.service.FakeVisionModelClient;
import com.modelcollab.vision.service.VisionDraftParser;
import com.modelcollab.vision.service.VisionImageValidator;
import com.modelcollab.vision.service.VisionImportService;
import com.modelcollab.vision.service.VisionMerger;
import com.modelcollab.vision.service.VisionModelClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CU17 completo: leer la foto, revisarla y aplicarla.
 *
 * <p>Lo que más se prueba acá no es que funcione, sino <b>lo que no puede pasar</b>: que una foto
 * se aplique sin confirmación, que la confirmación pise trabajo ajeno, y que el sub-grafo entre
 * como veinte eventos en vez de uno.
 */
@ExtendWith(MockitoExtension.class)
class VisionImportServiceTest {

    private static final UUID DIAGRAMA = UUID.randomUUID();
    private static final UUID USUARIO = UUID.randomUUID();

    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    /** Lector de fotos que devuelve un texto fijo y cuenta las llamadas. */
    private static class LectorFalso implements VisionModelClient {
        private String respuesta = """
            {"classes":[{"name":"Pedido","attributes":[{"name":"total","type":"DECIMAL"}]}],
             "relationships":[]}""";
        private RuntimeException aExplotar;
        private boolean simulado = true;
        private int llamadas;

        @Override
        public String leer(byte[] imagen, String tipoMime) {
            llamadas++;
            if (aExplotar != null) {
                throw aExplotar;
            }
            return respuesta;
        }

        @Override
        public String descripcion() {
            return "falso";
        }

        @Override
        public boolean esSimulado() {
            return simulado;
        }
    }

    @Mock private PreGenerationValidatorService estados;
    @Mock private SequenceService sequenceService;
    @Mock private DiagramMutationService mutationService;
    @Mock private DiagramOperationRepository operationRepository;

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private final LectorFalso lector = new LectorFalso();
    private final List<StompMutationMessage> aplicadas = new ArrayList<>();

    private VisionImportService servicio;

    @BeforeEach
    void preparar() {
        servicio = new VisionImportService(
                lector,
                new VisionImageValidator(),
                new VisionDraftParser(mapper),
                new VisionMerger(new GridLayoutEngine()),
                new MetamodelValidator(new IdentifierSanitizer()),
                estados,
                sequenceService,
                mutationService,
                operationRepository,
                mapper);

        lenient().when(estados.estadoDe(DIAGRAMA)).thenReturn(diagramaDePartida());
        lenient().when(sequenceService.tomarElDiagrama(any())).thenReturn(5L);
        lenient().when(operationRepository.findByDiagramIdAndClientMutationId(any(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(mutationService.aplicar(any(), any(), any())).thenAnswer(invocacion -> {
            StompMutationMessage mensaje = invocacion.getArgument(2);
            aplicadas.add(mensaje);
            return new DiagramMutationService.Resultado.Aplicada(
                    StompBroadcastMessage.de(mensaje, USUARIO.toString(), Instant.EPOCH, 6L),
                    OperationType.BULK_MERGE, null);
        });
    }

    private static CanonicalModel diagramaDePartida() {
        ClassEntity cliente = new ClassEntity(
                "cliente-1", "Cliente", null, false, new Position(20, 20), null, null,
                List.of(new Attribute("a1", "nombre", AttributeType.VARCHAR,
                        null, null, null, null, false, true, null, null)),
                List.of());
        return new CanonicalModel("1.0.0", 5L, List.of(), List.of(cliente), List.of());
    }

    // ------------------------------------------- analizar no puede escribir

    @Test
    @DisplayName("Analizar la foto NO toca el diagrama: es la mitad estructural del guardarraíl")
    void analizarNoAplica() {
        VisionProposalResponse propuesta = servicio.analizar(DIAGRAMA, PNG);

        assertThat(propuesta.draft().classes()).extracting(VisionClass::name)
                .containsExactly("Pedido");
        // El human-in-the-loop de RF-03.3 no es una promesa de la interfaz: no existe el camino
        // por el que una foto llegue al diagrama sin una segunda petición de una persona.
        verify(mutationService, never()).aplicar(any(), any(), any());
        verify(sequenceService, never()).tomarElDiagrama(any());
        verify(sequenceService, never()).reservar(any(), anyInt());
        assertThat(aplicadas).isEmpty();
    }

    @Test
    @DisplayName("La propuesta declara que es simulada: mostrarla sin avisar sería un truco")
    void declaraQueEsSimulada() {
        // Sin clave, la lectura no tiene ninguna relación con la foto que se subió. Enseñarla
        // como si fuera un reconocimiento sería una demostración que engaña al que mira.
        VisionProposalResponse propuesta = servicio.analizar(DIAGRAMA, PNG);

        assertThat(propuesta.simulated()).isTrue();
        assertThat(propuesta.message()).contains("NO corresponde a la imagen");

        lector.simulado = false;
        assertThat(servicio.analizar(DIAGRAMA, PNG).simulated()).isFalse();
    }

    @Test
    @DisplayName("Un archivo que no es imagen ni llega al servicio de visión")
    void archivoQueNoEsImagen() {
        assertThatThrownBy(() -> servicio.analizar(DIAGRAMA, new byte[] {'M', 'Z', 0, 0}))
                .isInstanceOf(VisionImageValidator.ImagenRechazadaException.class);

        // Se comprueba antes de gastar una llamada al servicio, que se paga.
        assertThat(lector.llamadas).isZero();
    }

    @Test
    @DisplayName("Si el servicio de visión no contesta, se dice eso y el diagrama no se toca")
    void servicioCaido() {
        lector.aExplotar = new IllegalStateException("connection refused");

        assertThatThrownBy(() -> servicio.analizar(DIAGRAMA, PNG))
                .isInstanceOf(RespuestaDeIaInvalidaException.class)
                .hasMessageContaining("no está disponible");
        assertThat(aplicadas).isEmpty();
    }

    @Test
    @DisplayName("Una respuesta que no es JSON no se convierte en propuesta")
    void respuestaMalformada() {
        lector.respuesta = "no pude leer la imagen, lo siento";

        assertThatThrownBy(() -> servicio.analizar(DIAGRAMA, PNG))
                .isInstanceOf(RespuestaDeIaInvalidaException.class);
    }

    @Test
    @DisplayName("Una lectura absurdamente grande se corta: es un límite de recursos, no el guardarraíl")
    void demasiadasClases() {
        StringBuilder clases = new StringBuilder();
        for (int i = 0; i < VisionDraftParser.MAXIMO_DE_CLASES + 1; i++) {
            clases.append(i > 0 ? "," : "")
                  .append("{\"name\":\"C").append(i).append("\",\"attributes\":[]}");
        }
        lector.respuesta = "{\"classes\":[" + clases + "],\"relationships\":[]}";

        assertThatThrownBy(() -> servicio.analizar(DIAGRAMA, PNG))
                .isInstanceOf(RespuestaDeIaInvalidaException.class);
    }

    // ------------------------------------------------------ confirmar aplica

    @Test
    @DisplayName("Confirmar aplica UN solo evento con todo el sub-grafo")
    void unSoloEvento() {
        VisionConfirmRequest peticion = new VisionConfirmRequest(
                new VisionDraft(
                        List.of(new VisionClass("Pedido", List.of()),
                                new VisionClass("Item", List.of()),
                                new VisionClass("Producto", List.of())),
                        List.of(new VisionRelationship(
                                "Pedido", "Item", "COMPOSITION", "1..1", "1..*"))),
                "cmd-1");

        VisionImportService.Resultado resultado =
                servicio.confirmar(DIAGRAMA, USUARIO, peticion);

        // Tres clases y una relación, una sola operación. Veinte parpadeos en el lienzo de cada
        // colaborador mientras entra un diagrama importado sería inaceptable, y además les
        // mostraría estados intermedios que nunca existieron como diagrama.
        assertThat(aplicadas).hasSize(1);
        assertThat(aplicadas.get(0).op()).isEqualTo("BULK_MERGE");
        assertThat(resultado.clasesNuevas()).isEqualTo(3);
        assertThat(resultado.relacionesNuevas()).isEqualTo(1);
    }

    @Test
    @DisplayName("El payload lleva lo que ya había MÁS lo nuevo: reemplazar no es perder")
    void elPayloadConservaLoQueHabia() throws Exception {
        servicio.confirmar(DIAGRAMA, USUARIO, new VisionConfirmRequest(
                new VisionDraft(List.of(new VisionClass("Pedido", List.of())), List.of()),
                "cmd-2"));

        CanonicalModel enviado = mapper.convertValue(
                aplicadas.get(0).payload(), CanonicalModel.class);

        // BULK_MERGE reemplaza el estado por el payload. Si el payload no trajera a Cliente,
        // confirmar una foto borraría el diagrama que ya estaba dibujado.
        assertThat(enviado.classes()).extracting(ClassEntity::name)
                .containsExactly("Cliente", "Pedido");
    }

    @Test
    @DisplayName("TOMA EL LOCK ANTES DE LEER: si no, una mutación ajena se borra en silencio")
    void tomaElLockAntesDeLeer() {
        servicio.confirmar(DIAGRAMA, USUARIO, new VisionConfirmRequest(
                new VisionDraft(List.of(new VisionClass("Pedido", List.of())), List.of()),
                "cmd-3"));

        // El orden es la corrección entera de esta operación. El payload de un BULK_MERGE es el
        // modelo completo calculado a partir de esta lectura: si otro colaborador commitea entre
        // la lectura y la aplicación, su cambio no queda en el payload y desaparece. No como un
        // conflicto que alguien pueda ver — como trabajo borrado sin rastro.
        InOrder orden = inOrder(sequenceService, estados, mutationService);
        orden.verify(sequenceService).tomarElDiagrama(DIAGRAMA);
        orden.verify(estados).estadoDe(DIAGRAMA);
        orden.verify(mutationService).aplicar(any(), any(), any());
    }

    @Test
    @DisplayName("Confirmar dos veces el mismo id no mete la foto dos veces")
    void confirmarDosVeces() {
        when(operationRepository.findByDiagramIdAndClientMutationId(DIAGRAMA, "cmd-4"))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(
                        com.modelcollab.diagram.model.DiagramOperation.class)));

        VisionImportService.Resultado resultado = servicio.confirmar(
                DIAGRAMA, USUARIO, new VisionConfirmRequest(
                        new VisionDraft(List.of(new VisionClass("Pedido", List.of())), List.of()),
                        "cmd-4"));

        assertThat(resultado.difusion()).isNull();
        assertThat(aplicadas).isEmpty();
    }

    @Test
    @DisplayName("Un borrador que rompería el diagrama no se aplica")
    void noPuedeRomperElDiagrama() {
        // El diagrama de partida no tiene herencia; el borrador le da dos padres a Pedido.
        VisionConfirmRequest peticion = new VisionConfirmRequest(
                new VisionDraft(
                        List.of(new VisionClass("Pedido", List.of()),
                                new VisionClass("Base", List.of())),
                        List.of(new VisionRelationship("Pedido", "Base", "INHERITANCE", null, null),
                                new VisionRelationship(
                                        "Pedido", "Cliente", "INHERITANCE", null, null))),
                "cmd-5");

        assertThatThrownBy(() -> servicio.confirmar(DIAGRAMA, USUARIO, peticion))
                .isInstanceOf(VisionMerger.BorradorInvalidoException.class)
                .hasMessageContaining("herencia");
        assertThat(aplicadas).isEmpty();
    }

    @Test
    @DisplayName("No hay tope de cinco operaciones: doce clases en una pizarra es un uso legítimo")
    void noHayTopeDeCinco() {
        // En CU16 el tope existe porque nadie media entre lo que se dicta y lo que se aplica.
        // Acá media una persona, y el tope se reemplaza por su confirmación.
        List<VisionClass> doce = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            doce.add(new VisionClass("Clase" + i, List.of()));
        }

        VisionImportService.Resultado resultado = servicio.confirmar(
                DIAGRAMA, USUARIO,
                new VisionConfirmRequest(new VisionDraft(doce, List.of()), "cmd-6"));

        assertThat(resultado.clasesNuevas()).isEqualTo(12);
    }

    @Test
    @DisplayName("El aviso del simulado nombra sus propias imperfecciones, para que se vean")
    void elSimuladoTraeImperfecciones() {
        // Si la lectura simulada fuera perfecta, el circuito de revisión se demostraría con la
        // persona apretando "aceptar" sin nada que revisar — el human-in-the-loop de mentira.
        assertThat(FakeVisionModelClient.imperfeccionesDeliberadas())
                .contains("decmal", "Deposito");

        VisionImportService servicioSimulado = new VisionImportService(
                new FakeVisionModelClient(), new VisionImageValidator(),
                new VisionDraftParser(mapper), new VisionMerger(new GridLayoutEngine()),
                new MetamodelValidator(new IdentifierSanitizer()), estados, sequenceService,
                mutationService, operationRepository, mapper);

        VisionProposalResponse propuesta = servicioSimulado.analizar(DIAGRAMA, PNG);

        assertThat(propuesta.notes()).isNotEmpty();
        assertThat(propuesta.notes().toString()).contains("decmal").contains("Deposito");
    }
}
