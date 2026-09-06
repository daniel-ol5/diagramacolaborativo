package com.modelcollab.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.modelcollab.collaboration.dto.Participante;
import com.modelcollab.collaboration.service.RoomManager;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoomManagerTest {

    private final RoomManager roomManager = new RoomManager();
    private final UUID sala = UUID.randomUUID();
    private final Participante ana = Participante.de(UUID.randomUUID(), "Ana");
    private final Participante beto = Participante.de(UUID.randomUUID(), "Beto");

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
    @DisplayName("Un mismo usuario en dos pestañas cuenta como dos sesiones")
    void dosPestanias() {
        // Cada pestaña es una sesión WebSocket distinta; cerrar una no puede sacar al usuario
        // de la sala en la otra.
        roomManager.entrar(sala, "sesion-1", ana);
        roomManager.entrar(sala, "sesion-2", ana);

        roomManager.salir("sesion-1");

        assertThat(roomManager.participantes(sala)).containsExactly(ana);
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
