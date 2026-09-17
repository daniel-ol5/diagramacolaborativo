package com.modelcollab.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelcollab.collaboration.dto.Participante;
import com.modelcollab.collaboration.service.RoomManager;
import com.modelcollab.vision.dto.VisionCaptureBroadcast;
import com.modelcollab.vision.dto.VisionCaptureResponse;
import com.modelcollab.vision.dto.VisionClass;
import com.modelcollab.vision.dto.VisionDraft;
import com.modelcollab.vision.dto.VisionProposalResponse;
import com.modelcollab.vision.service.VisionCaptureService;
import com.modelcollab.vision.service.VisionImportService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * La foto del celular va <b>a quien la sacó</b>, no a la sala.
 *
 * <h2>Lo que este archivo protege</h2>
 *
 * <p>Si la propuesta se difundiera por {@code /topic/diagrams/{id}}, el modal de revisión se
 * abriría en la pantalla de todos los colaboradores a la vez. Y revisar termina en confirmar:
 * tres personas confirmando la misma propuesta dejan el diagrama con <b>tres copias de cada
 * clase</b>. La red de la idempotencia —{@code UNIQUE (diagram_id, client_mutation_id)} de la
 * V2— atraparía algo de eso, pero la red no tiene que hacer falta.
 *
 * <h2>Lo que este archivo NO prueba, y hay que decirlo</h2>
 *
 * <p>Verifica que se <b>llamó</b> a {@code convertAndSendToUser} con el destino correcto.
 * <b>Eso no es lo mismo que haber llegado.</b> Un mock no tiene suscriptores: acepta cualquier
 * destino, incluido uno que en un servidor real no entregaría a nadie. Ya nos pasó con el roster.
 * La entrega de verdad se comprueba con un suscriptor STOMP real en
 * {@code evidencias/verificar-captura.mjs}, contra el servidor levantado.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VisionCaptureServiceTest {

    @Mock private VisionImportService importService;
    @Mock private RoomManager roomManager;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private VisionCaptureService servicio;

    private final UUID diagrama = UUID.randomUUID();
    private final UUID fotografo = UUID.randomUUID();
    private final byte[] foto = new byte[] {1, 2, 3};

    @BeforeEach
    void prepararLaSala() {
        servicio = new VisionCaptureService(importService, roomManager, messagingTemplate);
        when(importService.analizar(any(), any())).thenReturn(propuesta());
    }

    private static VisionProposalResponse propuesta() {
        return new VisionProposalResponse(
                true,
                "Revisá lo que leí",
                new VisionDraft(List.of(
                        new VisionClass("Cliente", List.of()),
                        new VisionClass("Pedido", List.of())),
                        List.of()),
                List.of());
    }

    private Participante enLaSala(UUID userId) {
        return new Participante(userId, "Daniel", "#006CD1");
    }

    @Test
    @DisplayName("La propuesta va al canal privado de quien sacó la foto, con el diagrama")
    void vaAlCanalPrivadoDelFotografo() {
        when(roomManager.participantes(diagrama)).thenReturn(List.of(enLaSala(fotografo)));

        VisionCaptureResponse respuesta = servicio.recibir(diagrama, fotografo, foto);

        ArgumentCaptor<String> destinatario = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> destino = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> cuerpo = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(
                destinatario.capture(), destino.capture(), cuerpo.capture());

        assertThat(destinatario.getValue()).isEqualTo(fotografo.toString());
        assertThat(destino.getValue()).isEqualTo(VisionCaptureService.COLA_VISION);

        VisionCaptureBroadcast mensaje = (VisionCaptureBroadcast) cuerpo.getValue();
        assertThat(mensaje.event()).isEqualTo(VisionCaptureBroadcast.EVENTO);
        assertThat(mensaje.source()).isEqualTo(VisionCaptureBroadcast.DESDE_EL_CELULAR);
        // Una cola de usuario es POR USUARIO, no por diagrama: con dos pestañas abiertas en dos
        // diagramas, este mensaje llega a las dos y sin el id la pestaña equivocada abriría el
        // modal. El campo es lo único que se lo impide.
        assertThat(mensaje.diagramId()).isEqualTo(diagrama);
        assertThat(mensaje.proposal().draft().classes()).hasSize(2);

        assertThat(respuesta.delivered()).isTrue();
        assertThat(respuesta.classes()).isEqualTo(2);
        // El aviso de simulado viaja también acá: el simulado no disimula en ninguna de las tres
        // respuestas del sistema.
        assertThat(respuesta.simulated()).isTrue();
    }

    @Test
    @DisplayName("Nunca se difunde a la sala: el modal se abriría en tres pantallas")
    void nuncaVaAlTopicDeLaSala() {
        when(roomManager.participantes(diagrama)).thenReturn(List.of(enLaSala(fotografo)));

        servicio.recibir(diagrama, fotografo, foto);

        // convertAndSend(destino, cuerpo) es la difusión a la sala. Si alguna vez se llama desde
        // acá, tres personas pueden confirmar la misma propuesta.
        verify(messagingTemplate, never()).convertAndSend(anyString(), (Object) any());
    }

    @Test
    @DisplayName("Sin nadie mirando no se analiza la foto: la lectura se paga")
    void sinPantallaNoSeGastaLaLectura() {
        when(roomManager.participantes(diagrama)).thenReturn(List.of());

        VisionCaptureResponse respuesta = servicio.recibir(diagrama, fotografo, foto);

        assertThat(respuesta.delivered()).isFalse();
        assertThat(respuesta.reason()).isEqualTo(VisionCaptureResponse.SIN_PANTALLA);
        assertThat(respuesta.message()).contains("computadora");

        // Lo importante no es el mensaje: es que NO se llamó al servicio de visión. Sin pantalla
        // escuchando, y sin almacenamiento, esa lectura se tiraría a la basura ya pagada.
        verify(importService, never()).analizar(any(), any());
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Que otro tenga el diagrama abierto NO alcanza: la propuesta es de quien fotografió")
    void unCompanieroMirandoNoAlcanza() {
        when(roomManager.participantes(diagrama))
                .thenReturn(List.of(enLaSala(UUID.randomUUID())));

        VisionCaptureResponse respuesta = servicio.recibir(diagrama, fotografo, foto);

        // Si esto entregara, el mensaje saldría al canal privado del fotógrafo —que no tiene a
        // nadie escuchando— y se perdería en silencio. Desde el teléfono se vería idéntico a un
        // éxito, que es la peor forma de fallar que hay acá.
        assertThat(respuesta.delivered()).isFalse();
        assertThat(respuesta.reason()).isEqualTo(VisionCaptureResponse.SIN_PANTALLA);
        verify(importService, never()).analizar(any(), any());
    }
}
