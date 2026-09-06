package com.modelcollab.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.modelcollab.collaboration.dto.LockBroadcastMessage;
import com.modelcollab.collaboration.dto.LockDeniedMessage;
import com.modelcollab.collaboration.dto.Participante;
import com.modelcollab.collaboration.service.LockManagerService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * <b>Criterio del Día 3: uno toma el lock, el segundo recibe {@code LOCK_DENIED}.</b>
 *
 * <p>El tiempo se controla con un reloj falso en vez de dormir el hilo: probar un TTL de 5000 ms
 * con {@code Thread.sleep} haría que la suite tardara medio minuto y, peor, que fallara de vez en
 * cuando en una máquina cargada. Un test de expiración que a veces falla es peor que no tenerlo,
 * porque enseña a ignorar la luz roja.
 */
@ExtendWith(MockitoExtension.class)
class LockManagerServiceTest {

    @Mock private SimpMessagingTemplate messagingTemplate;

    private RelojDePrueba reloj;
    private LockManagerService lockManager;

    private final UUID diagramId = UUID.randomUUID();
    private final String targetId = "clase-auto";
    private final Participante ana = Participante.de(UUID.randomUUID(), "Ana");
    private final Participante beto = Participante.de(UUID.randomUUID(), "Beto");

    /** Reloj que sólo avanza cuando el test se lo pide. */
    private static final class RelojDePrueba extends Clock {
        private long ahora = 1_000_000L;

        void avanzar(long ms) {
            ahora += ms;
        }

        @Override public long millis() { return ahora; }
        @Override public Instant instant() { return Instant.ofEpochMilli(ahora); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zona) { return this; }
    }

    @BeforeEach
    void prepararServicio() {
        reloj = new RelojDePrueba();
        lockManager = new LockManagerService(messagingTemplate, reloj);
    }

    @Test
    @DisplayName("CRITERIO DEL DÍA: Ana toma el lock y Beto recibe LOCK_DENIED por su canal")
    void elSegundoRecibeLockDenied() {
        var primera = lockManager.adquirir(diagramId, targetId, ana, "sesion-ana");
        assertThat(primera).isInstanceOf(LockManagerService.Resultado.Concedido.class);

        // La concesión se difunde a la sala entera: los demás tienen que pintar el candado.
        LockBroadcastMessage difundido = difusion();
        assertThat(difundido.event()).isEqualTo("LOCK_ACQUIRED");
        assertThat(difundido.targetId()).isEqualTo(targetId);
        assertThat(difundido.userId()).isEqualTo(ana.userId());
        assertThat(difundido.userName()).isEqualTo("Ana");
        assertThat(difundido.ttl()).isEqualTo(5000L);

        reloj.avanzar(1800); // Beto lo intenta mientras el lock sigue vivo
        var segunda = lockManager.adquirir(diagramId, targetId, beto, "sesion-beto");

        assertThat(segunda).isInstanceOf(LockManagerService.Resultado.Denegado.class);
        LockDeniedMessage denegado = denegacionA(beto);
        assertThat(denegado.event()).isEqualTo("LOCK_DENIED");
        assertThat(denegado.targetId()).isEqualTo(targetId);
        assertThat(denegado.heldBy()).isEqualTo("Ana");
        assertThat(denegado.expiresInMs())
                .as("el cliente necesita saber en cuánto puede reintentar")
                .isEqualTo(5000 - 1800);

        // El lock sigue siendo de Ana: una denegación no se lo quita.
        assertThat(lockManager.poseeLock(diagramId, targetId, ana.userId())).isTrue();
        assertThat(lockManager.poseeLock(diagramId, targetId, beto.userId())).isFalse();
    }

    @Test
    @DisplayName("La denegación va sólo al solicitante, no a la sala")
    void laDenegacionEsPrivada() {
        lockManager.adquirir(diagramId, targetId, ana, "sesion-ana");
        lockManager.adquirir(diagramId, targetId, beto, "sesion-beto");

        // Una sola difusión al topic: la de la concesión de Ana. La denegación de Beto no.
        verify(messagingTemplate, times(1)).convertAndSend(eq(topic()), any(Object.class));
        verify(messagingTemplate)
                .convertAndSendToUser(
                        eq(beto.userId().toString()), eq("/queue/locks"), any(Object.class));
    }

    @Test
    @DisplayName("Vencido el TTL, el lock queda libre para otro")
    void elLockVence() {
        lockManager.adquirir(diagramId, targetId, ana, "sesion-ana");

        reloj.avanzar(LockManagerService.TTL_MS + 1);

        assertThat(lockManager.poseeLock(diagramId, targetId, ana.userId())).isFalse();
        assertThat(lockManager.adquirir(diagramId, targetId, beto, "sesion-beto"))
                .isInstanceOf(LockManagerService.Resultado.Concedido.class);
    }

    @Test
    @DisplayName("El barrido emite LOCK_RELEASED al vencer: sin eso el candado no se despinta")
    void elBarridoEmiteLockReleased() {
        lockManager.adquirir(diagramId, targetId, ana, "sesion-ana");
        reloj.avanzar(LockManagerService.TTL_MS + 1);

        lockManager.barrerVencidos();

        // La comprobación perezosa no alcanza: si nadie pregunta, nadie avisa, y los demás
        // clientes se quedan con el candado dibujado para siempre.
        assertThat(ultimaDifusion().event()).isEqualTo("LOCK_RELEASED");
        assertThat(lockManager.cantidadDeLocks()).isZero();
    }

    @Test
    @DisplayName("El heartbeat renueva el TTL sin volver a difundir")
    void elHeartbeatRenuevaSinRuido() {
        lockManager.adquirir(diagramId, targetId, ana, "sesion-ana");
        reloj.avanzar(4000);

        var renovacion = lockManager.adquirir(diagramId, targetId, ana, "sesion-ana");

        assertThat(renovacion)
                .asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.type(
                                LockManagerService.Resultado.Concedido.class))
                .extracting(LockManagerService.Resultado.Concedido::renovacion)
                .isEqualTo(true);

        // Una sola difusión en total: renovar cada 2 s por usuario inundaría el canal.
        verify(messagingTemplate, times(1)).convertAndSend(eq(topic()), any(Object.class));

        // Y el TTL arranca de nuevo: pasados otros 4 s (8 desde el inicio) sigue vivo.
        reloj.avanzar(4000);
        assertThat(lockManager.poseeLock(diagramId, targetId, ana.userId())).isTrue();
    }

    @Test
    @DisplayName("Nadie puede liberar el lock de otro")
    void nadieLiberaElLockAjeno() {
        lockManager.adquirir(diagramId, targetId, ana, "sesion-ana");

        assertThat(lockManager.liberar(diagramId, targetId, beto.userId())).isFalse();
        assertThat(lockManager.poseeLock(diagramId, targetId, ana.userId())).isTrue();

        assertThat(lockManager.liberar(diagramId, targetId, ana.userId())).isTrue();
        assertThat(lockManager.poseeLock(diagramId, targetId, ana.userId())).isFalse();
    }

    @Test
    @DisplayName("Liberar un lock que no existe no difunde un LOCK_RELEASED fantasma")
    void liberarLoInexistenteNoDifunde() {
        assertThat(lockManager.liberar(diagramId, "no-existe", ana.userId())).isFalse();
        verify(messagingTemplate, never()).convertAndSend(eq(topic()), any(Object.class));
    }

    @Test
    @DisplayName("Al desconectarse una sesión se sueltan todos sus locks")
    void laDesconexionSueltaTodo() {
        lockManager.adquirir(diagramId, "clase-1", ana, "sesion-ana");
        lockManager.adquirir(diagramId, "clase-2", ana, "sesion-ana");
        lockManager.adquirir(diagramId, "clase-3", beto, "sesion-beto");

        int liberados = lockManager.liberarTodosDeLaSesion("sesion-ana");

        // Cerrar la pestaña no manda RELEASE_LOCK: sin esto, sus dos clases quedarían
        // bloqueadas para el resto de la sala hasta que venciera el TTL.
        assertThat(liberados).isEqualTo(2);
        assertThat(lockManager.poseeLock(diagramId, "clase-3", beto.userId())).isTrue();
        assertThat(lockManager.cantidadDeLocks()).isEqualTo(1);
    }

    @Test
    @DisplayName("Los locks son por diagrama: el mismo targetId en dos diagramas no colisiona")
    void losLocksSonPorDiagrama() {
        UUID otroDiagrama = UUID.randomUUID();
        lockManager.adquirir(diagramId, targetId, ana, "sesion-ana");

        assertThat(lockManager.adquirir(otroDiagrama, targetId, beto, "sesion-beto"))
                .isInstanceOf(LockManagerService.Resultado.Concedido.class);
    }

    @Test
    @DisplayName("El color de un usuario es estable entre reconexiones")
    void elColorEsEstable() {
        UUID userId = UUID.randomUUID();
        // Si el color se repartiera por orden de llegada, reconectarse cambiaría de color y el
        // resto de la sala vería "otra persona" editando.
        assertThat(Participante.de(userId, "Ana").color())
                .isEqualTo(Participante.de(userId, "Ana").color());
    }

    // ------------------------------------------------------------------ apoyo

    private String topic() {
        return "/topic/diagrams/" + diagramId;
    }

    private LockBroadcastMessage difusion() {
        return capturarDifusiones().getAllValues().get(0);
    }

    private LockBroadcastMessage ultimaDifusion() {
        return capturarDifusiones().getValue();
    }

    private ArgumentCaptor<LockBroadcastMessage> capturarDifusiones() {
        ArgumentCaptor<LockBroadcastMessage> captor =
                ArgumentCaptor.forClass(LockBroadcastMessage.class);
        verify(messagingTemplate, org.mockito.Mockito.atLeastOnce())
                .convertAndSend(eq(topic()), (Object) captor.capture());
        return captor;
    }

    private LockDeniedMessage denegacionA(Participante quien) {
        ArgumentCaptor<LockDeniedMessage> captor =
                ArgumentCaptor.forClass(LockDeniedMessage.class);
        verify(messagingTemplate)
                .convertAndSendToUser(
                        eq(quien.userId().toString()),
                        eq("/queue/locks"),
                        (Object) captor.capture());
        return captor.getValue();
    }
}
