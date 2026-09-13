package com.modelcollab.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.ai.dto.AiCommandRequest;
import com.modelcollab.ai.dto.AiCommandResponse;
import com.modelcollab.ai.service.AiCommandService;
import com.modelcollab.ai.service.AiModelClient;
import com.modelcollab.ai.service.AiPlanParser;
import com.modelcollab.ai.service.CommandGuardrail;
import com.modelcollab.ai.service.ComandoRechazadoException;
import com.modelcollab.ai.service.GridLayoutEngine;
import com.modelcollab.ai.service.OperationTranslator;
import com.modelcollab.ai.service.RespuestaDeIaInvalidaException;
import com.modelcollab.auth.repository.UserRepository;
import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.collaboration.dto.Participante;
import com.modelcollab.collaboration.dto.StompBroadcastMessage;
import com.modelcollab.collaboration.dto.StompMutationMessage;
import com.modelcollab.collaboration.service.CanonicalMutationApplier;
import com.modelcollab.collaboration.service.DiagramMutationService;
import com.modelcollab.collaboration.service.LockManagerService;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * CU16 de punta a punta, con el servicio de IA reemplazado por respuestas fijas.
 *
 * <h2>Las respuestas que importan son las malas</h2>
 *
 * <p>Que el camino feliz funcione no dice nada sobre el día que el modelo devuelva un {@code op}
 * inexistente, un tipo inventado o veinte operaciones de golpe. Esos son los casos que deciden si
 * la IA puede o no corromper el modelo canónico — y el canónico es lo que sostiene el lienzo de
 * todos los colaboradores, el generador de código y el exportador XMI.
 *
 * <p>El cliente falso permite provocarlos a voluntad y <b>contar las llamadas</b>, que es como se
 * comprueba lo que no se ve: que el guardarraíl rechaza sin gastar una llamada al servicio.
 */
@ExtendWith(MockitoExtension.class)
class AiCommandServiceTest {

    private static final UUID DIAGRAMA = UUID.randomUUID();
    private static final UUID USUARIO = UUID.randomUUID();
    private static final UUID OTRO = UUID.randomUUID();

    /** Cliente de IA que devuelve un texto fijo y cuenta cuántas veces lo llamaron. */
    private static class ClienteFalso implements AiModelClient {
        private String respuesta = "{\"intent\":\"UNSUPPORTED\",\"reply\":\"x\",\"operations\":[]}";
        private RuntimeException aExplotar;
        private int llamadas;

        @Override
        public String responder(String comando, List<String> clases) {
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
            return true;
        }
    }

    @Mock private PreGenerationValidatorService estados;
    @Mock private DiagramMutationService mutationService;
    @Mock private DiagramOperationRepository operationRepository;
    @Mock private UserRepository userRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private final ClienteFalso cliente = new ClienteFalso();
    private final List<StompMutationMessage> aplicadas = new ArrayList<>();

    private LockManagerService lockManager;
    private AiCommandService servicio;

    @BeforeEach
    void preparar() {
        lockManager = new LockManagerService(messagingTemplate);

        servicio = new AiCommandService(
                cliente,
                new CommandGuardrail(),
                new AiPlanParser(mapper),
                new OperationTranslator(
                        new CanonicalMutationApplier(mapper), new GridLayoutEngine(), mapper),
                new MetamodelValidator(new IdentifierSanitizer()),
                estados,
                mutationService,
                operationRepository,
                lockManager,
                userRepository);

        lenient().when(estados.estadoDe(DIAGRAMA)).thenReturn(diagramaDePartida());
        lenient().when(userRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(operationRepository.findByDiagramIdAndClientMutationId(any(), anyString()))
                .thenReturn(Optional.empty());

        lenient().when(mutationService.aplicar(any(), any(), any())).thenAnswer(invocacion -> {
            StompMutationMessage mensaje = invocacion.getArgument(2);
            aplicadas.add(mensaje);
            return new DiagramMutationService.Resultado.Aplicada(
                    StompBroadcastMessage.de(
                            mensaje, USUARIO.toString(), Instant.EPOCH, aplicadas.size()),
                    OperationType.valueOf(mensaje.op()),
                    mensaje.targetId());
        });
    }

    /** Dos clases y una herencia: alcanza para probar todo lo que hace falta. */
    private static CanonicalModel diagramaDePartida() {
        ClassEntity cliente = new ClassEntity(
                "cliente-1", "Cliente", null, false, new Position(20, 20), null, null,
                List.of(new Attribute("attr-1", "nombre", AttributeType.VARCHAR,
                        null, null, null, null, false, true, null, null)),
                List.of());
        ClassEntity proveedor = new ClassEntity(
                "proveedor-1", "Proveedor", null, false, new Position(360, 20), null, null,
                List.of(), List.of());
        ClassEntity persona = new ClassEntity(
                "persona-1", "Persona", null, true, new Position(700, 20), null, null,
                List.of(), List.of());

        Relationship herencia = new Relationship(
                "rel-1", "cliente-1", "persona-1", RelationshipType.INHERITANCE,
                null, null, null, null, null, null, null, List.of());

        return new CanonicalModel("1.0.0", 7L, List.of(),
                List.of(cliente, proveedor, persona), List.of(herencia));
    }

    private AiCommandService.Resultado ejecutar(String respuestaDelModelo) {
        cliente.respuesta = respuestaDelModelo;
        return servicio.procesar(DIAGRAMA, USUARIO, new AiCommandRequest("lo que sea", null));
    }

    // ------------------------------------------------------- el camino feliz

    @Test
    @DisplayName("Un comando válido entra por el MISMO camino que una mutación del lienzo")
    void entraPorElCaminoComun() {
        AiCommandService.Resultado resultado = ejecutar("""
            {"intent":"EDIT_MODEL","reply":"Creé Factura.","operations":[
              {"op":"ADD_CLASS","target":"Factura","args":{}},
              {"op":"ADD_ATTRIBUTE","target":"Factura","args":{"name":"total","type":"DECIMAL"}}
            ]}""");

        assertThat(resultado.respuesta().applied()).isTrue();
        assertThat(resultado.respuesta().operations()).hasSize(2);

        // El atajo sería escribir el JSONB directo. Esto comprueba que no se tomó: pasó por el
        // servicio de mutación, que es el que da número de secuencia, escribe la bitácora y
        // permite que el rebase offline vea una historia sin huecos.
        assertThat(aplicadas).hasSize(2);
        assertThat(aplicadas.get(0).op()).isEqualTo("ADD_CLASS");
        assertThat(aplicadas.get(1).op()).isEqualTo("ADD_ATTRIBUTE");
        assertThat(resultado.difusiones()).hasSize(2);
    }

    @Test
    @DisplayName("Una clase creada en el mismo comando ya existe para la operación siguiente")
    void elEnsayoEncadena() {
        // ADD_ATTRIBUTE apunta a 'Factura', que no existe en el diagrama de partida: sólo
        // funciona si la operación anterior ya la creó en la copia en memoria.
        ejecutar("""
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"ADD_CLASS","target":"Factura","args":{}},
              {"op":"ADD_ATTRIBUTE","target":"Factura","args":{"name":"total","type":"DECIMAL"}}
            ]}""");

        String idDeLaClaseNueva = (String) aplicadas.get(0).payload().get("id");
        assertThat(aplicadas.get(1).targetId()).isEqualTo(idDeLaClaseNueva);
    }

    @Test
    @DisplayName("La identidad la pone el servidor: la IA nunca elige un id")
    void laIdentidadEsDelServidor() {
        ejecutar("""
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"ADD_CLASS","target":"Factura","args":{}}]}""");

        String id = (String) aplicadas.get(0).payload().get("id");
        assertThat(id).isNotBlank();
        assertThatUuid(id);
        // Y la posición la puso la grilla, no el modelo: un comando no trae coordenadas.
        assertThat(aplicadas.get(0).payload()).containsKey("position");
    }

    @Test
    @DisplayName("Cada operación lleva su clientMutationId derivado del comando")
    void cadaOperacionEsIdempotente() {
        cliente.respuesta = """
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"ADD_CLASS","target":"Factura","args":{}},
              {"op":"ADD_CLASS","target":"Remito","args":{}}]}""";

        servicio.procesar(DIAGRAMA, USUARIO, new AiCommandRequest("x", "cmd-9"));

        // Sin esto, apretar dos veces "enviar" crea dos clases Factura.
        assertThat(aplicadas.stream().map(StompMutationMessage::clientMutationId))
                .containsExactly("ai-cmd-9-1", "ai-cmd-9-2");
    }

    @Test
    @DisplayName("Reintentar el mismo comando no lo aplica de nuevo ni vuelve a llamar al modelo")
    void reintentoIdempotente() {
        // El constructor sin argumentos de la entidad es protected (lo exige JPA), asi que se
        // usa un mock: lo unico que mira el servicio es si la fila existe o no.
        when(operationRepository.findByDiagramIdAndClientMutationId(DIAGRAMA, "ai-cmd-9-1"))
                .thenReturn(Optional.of(
                        org.mockito.Mockito.mock(
                                com.modelcollab.diagram.model.DiagramOperation.class)));

        AiCommandService.Resultado resultado =
                servicio.procesar(DIAGRAMA, USUARIO, new AiCommandRequest("x", "cmd-9"));

        assertThat(resultado.respuesta().reason()).isEqualTo(AiCommandResponse.YA_APLICADO);
        assertThat(cliente.llamadas).isZero();
        assertThat(aplicadas).isEmpty();
    }

    // --------------------------------------------------------- el guardarraíl

    @Test
    @DisplayName("Pedir un dominio entero se rechaza SIN gastar una llamada al servicio")
    void elGuardarrailNoLlamaANadie() {
        AiCommandService.Resultado resultado = servicio.procesar(
                DIAGRAMA, USUARIO, new AiCommandRequest("Créame un sistema para un banco", null));

        assertThat(resultado.respuesta().applied()).isFalse();
        assertThat(resultado.respuesta().reason()).isEqualTo(AiCommandResponse.GENERAR_DOMINIO);
        assertThat(resultado.respuesta().message()).isEqualTo(CommandGuardrail.RECHAZO);
        // Ésta es la afirmación que vale: el rechazo funciona con la clave vencida, sin
        // internet y con el servicio caído. La respuesta a la pregunta de §19 no depende de la red.
        assertThat(cliente.llamadas).isZero();
        verify(mutationService, never()).aplicar(any(), any(), any());
    }

    @Test
    @DisplayName("Si el modelo se autodenuncia GENERATE_DOMAIN, se le cree para rechazar")
    void elModeloSeAutodenuncia() {
        AiCommandService.Resultado resultado = ejecutar(
                "{\"intent\":\"GENERATE_DOMAIN\",\"reply\":\"...\",\"operations\":[]}");

        assertThat(resultado.respuesta().reason()).isEqualTo(AiCommandResponse.GENERAR_DOMINIO);
        assertThat(aplicadas).isEmpty();
    }

    @Test
    @DisplayName("Veinte operaciones no es un comando: tope duro de 5")
    void topeDuroDeCinco() {
        StringBuilder ops = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            ops.append(i > 0 ? "," : "")
               .append("{\"op\":\"ADD_CLASS\",\"target\":\"Clase").append(i).append("\",\"args\":{}}");
        }
        AiCommandService.Resultado resultado = ejecutar(
                "{\"intent\":\"EDIT_MODEL\",\"reply\":\"x\",\"operations\":[" + ops + "]}");

        assertThat(resultado.respuesta().reason())
                .isEqualTo(AiCommandResponse.DEMASIADAS_OPERACIONES);
        // No se recortan a cinco: medio plan aplicado es un diagrama que nadie pidió.
        assertThat(aplicadas).isEmpty();
        assertThat(AiCommandService.MAXIMO_DE_OPERACIONES).isEqualTo(5);
    }

    @Test
    @DisplayName("Exactamente 5 operaciones pasan: el tope no se corre de lugar")
    void cincoJustasPasan() {
        StringBuilder ops = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            ops.append(i > 0 ? "," : "")
               .append("{\"op\":\"ADD_CLASS\",\"target\":\"Clase").append(i).append("\",\"args\":{}}");
        }
        AiCommandService.Resultado resultado = ejecutar(
                "{\"intent\":\"EDIT_MODEL\",\"reply\":\"x\",\"operations\":[" + ops + "]}");

        assertThat(resultado.respuesta().applied()).isTrue();
        assertThat(aplicadas).hasSize(5);
    }

    // ------------------------------------------- la IA devolviendo cualquier cosa

    @Test
    @DisplayName("JSON malformado: el diagrama no se toca")
    void jsonMalformado() {
        cliente.respuesta = "lo siento, no puedo ayudarte con eso";

        assertThatThrownBy(() -> servicio.procesar(
                DIAGRAMA, USUARIO, new AiCommandRequest("x", null)))
                .isInstanceOf(RespuestaDeIaInvalidaException.class);
        assertThat(aplicadas).isEmpty();
    }

    @Test
    @DisplayName("Una operación inexistente no entra al canónico")
    void operacionInexistente() {
        cliente.respuesta = """
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"DELETE_EVERYTHING","target":"Cliente","args":{}}]}""";

        assertThatThrownBy(() -> servicio.procesar(
                DIAGRAMA, USUARIO, new AiCommandRequest("x", null)))
                .isInstanceOf(RespuestaDeIaInvalidaException.class);
        assertThat(aplicadas).isEmpty();
    }

    @Test
    @DisplayName("Un tipo de atributo inventado no entra: el síntoma aparecería en el generador")
    void tipoInventado() {
        cliente.respuesta = """
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"ADD_ATTRIBUTE","target":"Cliente","args":{"name":"saldo","type":"MONEY"}}]}""";

        // Si MONEY entrara, la clase quedaría en el canónico con un tipo que el generador no
        // sabe traducir, y el fallo aparecería a días de distancia como un ZIP que no compila.
        assertThatThrownBy(() -> servicio.procesar(
                DIAGRAMA, USUARIO, new AiCommandRequest("x", null)))
                .isInstanceOf(RespuestaDeIaInvalidaException.class)
                .hasMessageContaining("MONEY");
        assertThat(aplicadas).isEmpty();
    }

    @Test
    @DisplayName("Un campo que la operación no admite se rechaza")
    void campoQueNoCorresponde() {
        cliente.respuesta = """
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"ADD_CLASS","target":"Factura","args":{"id":"me-elijo-el-id"}}]}""";

        assertThatThrownBy(() -> servicio.procesar(
                DIAGRAMA, USUARIO, new AiCommandRequest("x", null)))
                .isInstanceOf(RespuestaDeIaInvalidaException.class)
                .hasMessageContaining("id");
    }

    @Test
    @DisplayName("Si el servicio no contesta se dice que no contestó, y no otra cosa")
    void servicioCaido() {
        cliente.aExplotar = new IllegalStateException("connection refused");

        assertThatThrownBy(() -> servicio.procesar(
                DIAGRAMA, USUARIO, new AiCommandRequest("x", null)))
                .isInstanceOf(RespuestaDeIaInvalidaException.class)
                .hasMessageContaining("no está disponible");
        assertThat(aplicadas).isEmpty();
    }

    // ------------------------------------------------- contra el diagrama real

    @Test
    @DisplayName("Nombrar una clase que no existe es un rechazo con nombre y apellido")
    void claseInexistente() {
        cliente.respuesta = """
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"ADD_ATTRIBUTE","target":"Fractura","args":{"name":"total","type":"DECIMAL"}}]}""";

        // Lo más común es que el usuario se acuerde mal del nombre, o que el dictado lo
        // transcriba mal. Decirle cuál nombró es lo único que le sirve.
        assertThatThrownBy(() -> servicio.procesar(
                DIAGRAMA, USUARIO, new AiCommandRequest("x", null)))
                .isInstanceOf(ComandoRechazadoException.class)
                .hasMessageContaining("Fractura");
    }

    @Test
    @DisplayName("La IA no puede dejar el diagrama peor de lo que estaba")
    void noPuedeRomperElDiagrama() {
        // Cliente ya hereda de Persona. Agregarle un segundo padre da herencia múltiple, que
        // Java no soporta y que el generador no puede resolver.
        cliente.respuesta = """
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"ADD_RELATIONSHIP","target":"Cliente",
               "args":{"to":"Proveedor","type":"INHERITANCE"}}]}""";

        AiCommandService.Resultado resultado = servicio.procesar(
                DIAGRAMA, USUARIO, new AiCommandRequest("x", null));

        assertThat(resultado.respuesta().reason())
                .isEqualTo(AiCommandResponse.ROMPERIA_EL_DIAGRAMA);
        assertThat(resultado.respuesta().message()).contains("herencia");
        assertThat(aplicadas).isEmpty();
    }

    @Test
    @DisplayName("Un atributo repetido se rechaza antes de duplicarlo")
    void atributoRepetido() {
        cliente.respuesta = """
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"ADD_ATTRIBUTE","target":"Cliente","args":{"name":"nombre","type":"VARCHAR"}}]}""";

        assertThatThrownBy(() -> servicio.procesar(
                DIAGRAMA, USUARIO, new AiCommandRequest("x", null)))
                .isInstanceOf(ComandoRechazadoException.class)
                .hasMessageContaining("ya tiene un atributo");
    }

    // ---------------------------------------------------------- exclusión mutua

    @Test
    @DisplayName("La IA NO puentea la exclusión mutua: si otro edita la clase, no se aplica")
    void noPuenteaElLock() {
        // Sin esto, el endpoint sería la puerta de atrás: lo que el lienzo impide se
        // conseguiría dictándolo.
        lockManager.adquirir(DIAGRAMA, "cliente-1",
                Participante.de(OTRO, "Vera"), "sesion-de-vera");

        cliente.respuesta = """
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"ADD_ATTRIBUTE","target":"Cliente","args":{"name":"saldo","type":"DECIMAL"}}]}""";

        AiCommandService.Resultado resultado = servicio.procesar(
                DIAGRAMA, USUARIO, new AiCommandRequest("x", null));

        assertThat(resultado.respuesta().reason()).isEqualTo(AiCommandResponse.ELEMENTO_BLOQUEADO);
        assertThat(resultado.respuesta().message()).contains("Vera");
        assertThat(aplicadas).isEmpty();
    }

    @Test
    @DisplayName("Toma el lock para aplicar y lo devuelve al terminar")
    void tomaYDevuelveElLock() {
        cliente.respuesta = """
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"ADD_ATTRIBUTE","target":"Cliente","args":{"name":"saldo","type":"DECIMAL"}}]}""";

        servicio.procesar(DIAGRAMA, USUARIO, new AiCommandRequest("x", null));

        // No queda tomado: este "colaborador" no tiene sesión WebSocket, así que nunca va a
        // llegar un DISCONNECT que lo libere. Si no se soltara acá, la clase quedaría trabada
        // hasta que venciera el TTL.
        assertThat(lockManager.poseeLock(DIAGRAMA, "cliente-1", USUARIO)).isFalse();
    }

    @Test
    @DisplayName("Si el usuario YA tenía el lock, no se lo quita al terminar")
    void noLeSacaElLockAlQueYaLoTenia() {
        // Está editando Cliente en el lienzo y dicta un comando sobre esa misma clase. Soltarle
        // el candado lo dejaría sin protección en medio de su propia edición, y el de al lado
        // podría entrar.
        lockManager.adquirir(DIAGRAMA, "cliente-1",
                Participante.de(USUARIO, "Ana"), "sesion-de-ana");

        cliente.respuesta = """
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"ADD_ATTRIBUTE","target":"Cliente","args":{"name":"saldo","type":"DECIMAL"}}]}""";

        servicio.procesar(DIAGRAMA, USUARIO, new AiCommandRequest("x", null));

        assertThat(lockManager.poseeLock(DIAGRAMA, "cliente-1", USUARIO)).isTrue();
    }

    // ------------------------------------------------------------- respuestas

    @Test
    @DisplayName("Le dice al usuario qué entendió, que es lo único que tiene el que dictó")
    void cuentaQueEntendio() {
        AiCommandService.Resultado resultado = ejecutar("""
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"ADD_ATTRIBUTE","target":"Cliente","args":{"name":"saldo","type":"DECIMAL"}}]}""");

        assertThat(resultado.respuesta().operations())
                .containsExactly("Agregué el atributo 'saldo' (DECIMAL) a 'Cliente'.");
    }

    @Test
    @DisplayName("Sin operaciones, se admite que no se entendió en vez de hacer algo parecido")
    void sinOperaciones() {
        AiCommandService.Resultado resultado = ejecutar(
                "{\"intent\":\"UNSUPPORTED\",\"reply\":\"No entendí.\",\"operations\":[]}");

        assertThat(resultado.respuesta().reason()).isEqualTo(AiCommandResponse.NO_ENTENDIDO);
        assertThat(aplicadas).isEmpty();
    }

    @Test
    @DisplayName("Un 'reply' desmesurado se recorta antes de llegar a la pantalla")
    void replyDesmesurado() {
        String largo = "a".repeat(5000);
        AiCommandService.Resultado resultado = ejecutar(
                "{\"intent\":\"EDIT_MODEL\",\"reply\":\"" + largo + "\",\"operations\":["
                        + "{\"op\":\"ADD_CLASS\",\"target\":\"Factura\",\"args\":{}}]}");

        assertThat(resultado.respuesta().message().length()).isLessThan(400);
    }

    private static void assertThatUuid(String valor) {
        assertThat(UUID.fromString(valor)).isNotNull();
    }
}
