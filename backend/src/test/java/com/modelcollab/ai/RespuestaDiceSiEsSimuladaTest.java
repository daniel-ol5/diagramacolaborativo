package com.modelcollab.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.modelcollab.ai.controller.AiCommandController;
import com.modelcollab.ai.dto.AiCommandRequest;
import com.modelcollab.ai.dto.AiCommandResponse;
import com.modelcollab.ai.service.AiCommandService;
import com.modelcollab.ai.service.AiModelClient;
import com.modelcollab.ai.service.ComandoRechazadoException;
import com.modelcollab.ai.service.RespuestaDeIaInvalidaException;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.repository.ProjectMemberRepository;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.security.Principal;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * <b>Ninguna respuesta de CU16 puede salir sin decir si la contestó un modelo de verdad.</b>
 *
 * <h2>Por qué hace falta un test y no alcanza con el campo</h2>
 *
 * <p>Agregar {@code simulated} al DTO no garantiza nada: lo que importa es que <i>todas</i> las
 * puertas de salida lo rellenen. Hoy son tres —la respuesta normal, el rechazo educado y el 502
 * del contrato roto— y mañana puede haber una cuarta. El que se olvide va a devolver
 * {@code false}, que es el valor por defecto de un {@code boolean} y que significa
 * <b>"esto lo contestó un modelo real"</b>.
 *
 * <p>Esa es la parte incómoda: <b>la omisión no se ve</b>. Una respuesta enlatada sin el aviso se
 * muestra exactamente igual que una real, y quien la mire —un tribunal incluido— puede creer que
 * está viendo funcionar algo que no está conectado. Cuando eso se descubre, y se descubre porque
 * alguien escribe una frase distinta y el sistema no entiende nada, pone en duda todo lo demás,
 * incluido lo que sí funciona.
 *
 * <h2>Por qué se enumeran los métodos por reflexión</h2>
 *
 * <p>Escribir tres casos a mano probaría las tres puertas de hoy y <b>no diría nada de la
 * cuarta</b>, que es justamente la que se va a olvidar. Se recorre la clase entera: todo método
 * público que devuelva una {@code ResponseEntity<AiCommandResponse>} entra solo. Un endpoint
 * nuevo queda cubierto sin que nadie se acuerde de venir hasta acá.
 *
 * <p>Es la misma familia de defecto que el {@code mappedBy} que no compilaba y que el prefijo
 * {@code /api} del cliente móvil: una regla que varios sitios tienen que respetar y que nadie
 * confronta. La respuesta también es la misma: un solo lugar que la aplica, y un guardián que
 * comprueba que no haya sitios por fuera.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RespuestaDiceSiEsSimuladaTest {

    @Mock private AiCommandService commandService;
    @Mock private AiModelClient cliente;
    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private AiCommandController controlador;

    @BeforeEach
    void prepararElCamino() {
        controlador = new AiCommandController(
                commandService, cliente, projectMemberRepository, messagingTemplate);

        when(projectMemberRepository.findRoleByDiagramAndUser(any(), any()))
                .thenReturn(Optional.of(ProjectRole.EDITOR));
        when(commandService.procesar(any(), any(), any()))
                .thenReturn(new AiCommandService.Resultado(
                        AiCommandResponse.aplicada(
                                List.of("Se agrego la clase Cliente"), "Listo", 7L),
                        List.of()));
    }

    @Test
    @DisplayName("Con el cliente simulado, TODA salida del controlador dice simulated=true")
    void todaSalidaAvisaQueEsSimulada() throws Exception {
        when(cliente.esSimulado()).thenReturn(true);

        List<Method> puertas = puertasDeSalida();
        assertThat(puertas)
                .as("no se encontro ninguna salida: el test no esta probando nada")
                .hasSizeGreaterThanOrEqualTo(3);

        for (Method puerta : puertas) {
            assertThat(invocar(puerta).simulated())
                    .as("la salida %s devuelve una respuesta simulada SIN avisarlo",
                            puerta.getName())
                    .isTrue();
        }
    }

    /**
     * La dirección inversa, y no es simetría de adorno.
     *
     * <p>Sin esto, un {@code simulated = true} escrito a mano en el controlador pasaría el test
     * de arriba con las tres puertas en verde. Lo que se comprueba acá es que el valor <b>viene
     * del cliente</b>: cuando llegue la clave de Gemini el aviso tiene que desaparecer solo, sin
     * que nadie toque el código.
     */
    @Test
    @DisplayName("Con un cliente real, TODA salida dice simulated=false sin tocar nada")
    void conModeloRealElAvisoDesapareceSolo() throws Exception {
        when(cliente.esSimulado()).thenReturn(false);

        for (Method puerta : puertasDeSalida()) {
            assertThat(invocar(puerta).simulated())
                    .as("la salida %s avisa de un simulado que no existe", puerta.getName())
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------ el recorrido

    /** Todo método público del controlador que devuelva una respuesta de CU16. */
    private static List<Method> puertasDeSalida() {
        List<Method> puertas = new ArrayList<>();
        for (Method metodo : AiCommandController.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(metodo.getModifiers()) || metodo.isSynthetic()) {
                continue;
            }
            Type devuelto = metodo.getGenericReturnType();
            if (devuelto instanceof ParameterizedType parametrizado
                    && parametrizado.getRawType().equals(ResponseEntity.class)
                    && parametrizado.getActualTypeArguments()[0].equals(AiCommandResponse.class)) {
                puertas.add(metodo);
            }
        }
        return puertas;
    }

    @SuppressWarnings("unchecked")
    private AiCommandResponse invocar(Method puerta) throws Exception {
        Object[] argumentos = new Object[puerta.getParameterCount()];
        for (int i = 0; i < argumentos.length; i++) {
            argumentos[i] = argumentoPara(puerta.getParameterTypes()[i], puerta);
        }
        ResponseEntity<AiCommandResponse> respuesta =
                (ResponseEntity<AiCommandResponse>) puerta.invoke(controlador, argumentos);

        assertThat(respuesta.getBody())
                .as("la salida %s devolvio un cuerpo vacio", puerta.getName())
                .isNotNull();
        return respuesta.getBody();
    }

    /**
     * Un argumento plausible para cada tipo que el controlador sabe recibir.
     *
     * <p>Si aparece un tipo nuevo se rompe acá con un mensaje que lo dice, en vez de dejar pasar
     * un {@code null} que haría fallar la puerta por un motivo que no tiene nada que ver.
     */
    private static Object argumentoPara(Class<?> tipo, Method puerta) {
        if (tipo == UUID.class) {
            return UUID.randomUUID();
        }
        if (tipo == AiCommandRequest.class) {
            return new AiCommandRequest("agrega la clase Cliente", "cmd-1");
        }
        if (tipo == Principal.class) {
            String usuario = UUID.randomUUID().toString();
            return (Principal) () -> usuario;
        }
        if (tipo == ComandoRechazadoException.class) {
            return new ComandoRechazadoException(
                    AiCommandResponse.NO_ENTENDIDO, "No entendi ese comando.");
        }
        if (tipo == RuntimeException.class) {
            // El manejador generico de fallos inesperados. Lo pidio ESTE test al aparecer una
            // cuarta puerta de salida — que es exactamente para lo que se escribio por
            // reflexion en vez de a mano.
            return new IllegalStateException("algo se rompio");
        }
        if (tipo == RespuestaDeIaInvalidaException.class) {
            return new RespuestaDeIaInvalidaException(
                    AiCommandResponse.CONTRATO_ROTO, "El modelo devolvio algo que no es JSON.");
        }
        throw new AssertionError("la salida " + puerta.getName() + " recibe un "
                + tipo.getSimpleName() + " que este test no sabe construir: agregalo aca");
    }
}
