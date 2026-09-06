package com.modelcollab.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.modelcollab.collaboration.dto.Participante;
import com.modelcollab.collaboration.dto.PresenceMessage;
import com.modelcollab.collaboration.service.RoomManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class RoomManagerTest {

    @Mock private SimpMessagingTemplate messagingTemplate;

    private final UUID sala = UUID.randomUUID();
    private final Participante ana = Participante.de(UUID.randomUUID(), "Ana");
    private final Participante beto = Participante.de(UUID.randomUUID(), "Beto");

    private RoomManager roomManager;

    @BeforeEach
    void prepararSala() {
        roomManager = new RoomManager(messagingTemplate);
    }

    @Test
    @DisplayName("Lleva la cuenta de quién está en cada sala")
    void entrarYSalir() {
        roomManager.entrar(sala, "sesion-ana", ana);
        roomManager.entrar(sala, "sesion-beto", beto);

        assertThat(roomManager.participantes(sala)).containsExactlyInAnyOrder(ana, beto);

        assertThat(roomManager.salir("sesion-ana")).contains(sala);
        assertThat(roomManager.participantes(sala)).containsExactly(beto);
    }

    @Test
    @DisplayName("La sala se descarta cuando queda vacía: un mapa que sólo crece es una fuga")
    void laSalaVaciaSeDescarta() {
        roomManager.entrar(sala, "sesion-ana", ana);
        roomManager.salir("sesion-ana");

        assertThat(roomManager.salasActivas()).isZero();
        assertThat(roomManager.participantes(sala)).isEmpty();
    }

    @Test
    @DisplayName("Salir de una sesión que no estaba en ninguna sala no rompe nada")
    void salirSinHaberEntrado() {
        assertThat(roomManager.salir("sesion-fantasma")).isEmpty();
    }

    @Test
    @DisplayName("Un mismo usuario en dos pestañas cuenta como dos sesiones, pero una persona")
    void dosPestanias() {
        // Cada pestaña es una sesión WebSocket distinta; cerrar una no puede sacar al usuario
        // de la sala en la otra. Pero la sala lo dibuja una sola vez.
        roomManager.entrar(sala, "sesion-1", ana);
        roomManager.entrar(sala, "sesion-2", ana);
        assertThat(roomManager.participantes(sala)).containsExactly(ana);

        roomManager.salir("sesion-1");

        assertThat(roomManager.participantes(sala)).containsExactly(ana);
    }

    @Test
    @DisplayName("Al entrar alguien, la sala recibe el roster completo")
    void difundeElRosterAlEntrar() {
        roomManager.entrar(sala, "sesion-ana", ana);
        roomManager.entrar(sala, "sesion-beto", beto);

        // Va la lista entera y no un "entró fulano": con eventos incrementales, un cliente que
        // se pierde uno queda con un roster equivocado para siempre y sin forma de notarlo.
        assertThat(ultimoRoster().participants()).containsExactlyInAnyOrder(ana, beto);
        assertThat(ultimoRoster().event()).isEqualTo("PRESENCE");
    }

    @Test
    @DisplayName("Entrar NO empuja el roster al privado: eso tenía la misma carrera que el topic")
    void entrarNoEmpujaAlPrivado() {
        roomManager.entrar(sala, "sesion-ana", ana);

        // Se intentó y no servía: el canal privado del recién llegado se registra en el broker
        // por el mismo camino que el topic, así que empujárselo al entrar llega igual de tarde.
        // La verificación real lo destapó — el unitario confirmaba que se llamaba al template,
        // que se llamaba, sin que hubiera nadie escuchando.
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any(Object.class));
    }

    @Test
    @DisplayName("El roster se responde cuando el cliente lo pide, ya suscrito a todo")
    void respondeElRosterCuandoLoPiden() {
        roomManager.entrar(sala, "sesion-ana", ana);
        roomManager.entrar(sala, "sesion-beto", beto);

        roomManager.responderRoster(sala, ana.userId());

        ArgumentCaptor<PresenceMessage> captor = ArgumentCaptor.forClass(PresenceMessage.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq(ana.userId().toString()), eq("/queue/presence"), (Object) captor.capture());
        assertThat(captor.getValue().participants()).containsExactlyInAnyOrder(ana, beto);
    }

    @Test
    @DisplayName("Al salir alguien, los que quedan reciben el roster actualizado")
    void difundeElRosterAlSalir() {
        roomManager.entrar(sala, "sesion-ana", ana);
        roomManager.entrar(sala, "sesion-beto", beto);

        roomManager.salir("sesion-beto");

        assertThat(ultimoRoster().participants()).containsExactly(ana);
    }

    @Test
    @DisplayName("Si la sala queda vacía no se difunde a nadie")
    void salaVaciaNoDifunde() {
        roomManager.entrar(sala, "sesion-ana", ana);
        roomManager.salir("sesion-ana");

        // Difundir a un topic sin suscriptores es trabajo puro para nada.
        verify(messagingTemplate, times(1)).convertAndSend(eq(topic()), any(Object.class));
    }

    @Test
    @DisplayName("Salir de una sesión que no estaba en ninguna sala no difunde nada")
    void salirSinHaberEntradoNoDifunde() {
        roomManager.salir("sesion-fantasma");
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    private String topic() {
        return "/topic/diagrams/" + sala;
    }

    private PresenceMessage ultimoRoster() {
        ArgumentCaptor<PresenceMessage> captor = ArgumentCaptor.forClass(PresenceMessage.class);
        verify(messagingTemplate, org.mockito.Mockito.atLeastOnce())
                .convertAndSend(eq(topic()), (Object) captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("Las salas son independientes entre diagramas")
    void salasIndependientes() {
        UUID otraSala = UUID.randomUUID();
        roomManager.entrar(sala, "sesion-ana", ana);
        roomManager.entrar(otraSala, "sesion-beto", beto);

        assertThat(roomManager.participantes(sala)).containsExactly(ana);
        assertThat(roomManager.participantes(otraSala)).containsExactly(beto);
        assertThat(roomManager.salasActivas()).isEqualTo(2);
    }
}
