package com.modelcollab.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.modelcollab.ai.controller.AiCommandController;
import com.modelcollab.ai.dto.AiCommandResponse;
import com.modelcollab.ai.service.AiCommandService;
import com.modelcollab.ai.service.AiModelClient;
import com.modelcollab.project.repository.ProjectMemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Un fallo inesperado de CU16 sale con un cuerpo que se puede leer, y <b>nada más</b> cambia de
 * estado.
 *
 * <h2>Las dos mitades, y la segunda es la que puede hacer daño</h2>
 *
 * <p>La primera es obvia: un <b>500 con el cuerpo vacío</b> deja al usuario sin saber si se
 * aplicó algo. Dictó una orden, no pasó nada, y no hay ni un texto que leer.
 *
 * <p>La segunda es la peligrosa. Un manejador de {@code RuntimeException} es <b>exactamente</b>
 * la forma de repetir el incidente que ya tuvo este proyecto: la cadena de seguridad reevaluaba
 * el reenvío a {@code /error} y <b>toda respuesta de error salía convertida en 403 con cuerpo
 * vacío</b>. Los 409, los 401, los 400 — todos aplastados en uno.
 *
 * <p>Por eso acá no se prueba sólo el caso que se estaba arreglando: se prueban <b>varios
 * códigos</b>, y lo que se exige es que cada uno siga siendo lo que era.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SinQuinientosPeladoTest {

    @Mock private AiCommandService commandService;
    @Mock private AiModelClient cliente;
    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private AiCommandController controlador;

    @BeforeEach
    void prepararlo() {
        controlador = new AiCommandController(
                commandService, cliente, projectMemberRepository, messagingTemplate);
        when(cliente.esSimulado()).thenReturn(true);
        when(projectMemberRepository.findRoleByDiagramAndUser(any(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("Un fallo no previsto da 500 CON cuerpo: el usuario sabe que no se tocó nada")
    void elFalloInesperadoTraeCuerpo() {
        ResponseEntity<AiCommandResponse> r =
                controlador.falloInesperado(new IllegalStateException("algo se rompió"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().reason()).isEqualTo(AiCommandResponse.ERROR_INESPERADO);
        // Lo único que el usuario necesita saber con certeza cuando algo se rompió.
        assertThat(r.getBody().message()).contains("no se tocó");
        assertThat(r.getBody().applied()).isFalse();
        // Y sigue diciendo de dónde salió, como todas las demás salidas.
        assertThat(r.getBody().simulated()).isTrue();
    }

    @Test
    @DisplayName("El 403 de permisos SIGUE siendo 403, no se lo come el manejador")
    void elPermisoNoSeConvierteEnQuinientos() {
        // Es el incidente que ya tuvimos, en su forma exacta: un manejador demasiado ancho que
        // aplasta el estado que la excepción ya traía.
        ResponseStatusException prohibido = new ResponseStatusException(
                HttpStatus.FORBIDDEN, "No tenés permiso de escritura sobre este diagrama");

        assertThatThrownBy(() -> controlador.falloInesperado(prohibido))
                .isSameAs(prohibido);
    }

    @Test
    @DisplayName("Un cuerpo ilegible SIGUE siendo 400")
    void elCuerpoIlegibleNoSeConvierteEnQuinientos() {
        HttpMessageNotReadableException ilegible =
                new HttpMessageNotReadableException("JSON roto", (org.springframework.http.HttpInputMessage) null);

        assertThatThrownBy(() -> controlador.falloInesperado(ilegible)).isSameAs(ilegible);
    }

    @Test
    @DisplayName("Y los dos rechazos de CU16 siguen teniendo su propio manejador")
    void losRechazosDeSiempreNoCambian() {
        // Spring elige el manejador MÁS ESPECÍFICO, así que estos no pasan por el genérico. Se
        // comprueba igual: si alguien borrara esos manejadores, el genérico se los tragaría en
        // silencio y un rechazo educado pasaría a ser un 500.
        ResponseEntity<AiCommandResponse> rechazo = controlador.rechazo(
                new com.modelcollab.ai.service.ComandoRechazadoException(
                        AiCommandResponse.NO_ENTENDIDO, "No entendí"));
        assertThat(rechazo.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<AiCommandResponse> fallo = controlador.falloDeLaIa(
                new com.modelcollab.ai.service.RespuestaDeIaInvalidaException(
                        AiCommandResponse.IA_CAIDA, "El asistente no está disponible"));
        assertThat(fallo.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
