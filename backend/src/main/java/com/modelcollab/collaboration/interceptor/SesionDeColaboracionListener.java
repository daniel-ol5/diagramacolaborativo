package com.modelcollab.collaboration.interceptor;

import com.modelcollab.collaboration.dto.Participante;
import com.modelcollab.collaboration.service.LockManagerService;
import com.modelcollab.collaboration.service.RoomManager;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * Ata el ciclo de vida de la sesión WebSocket al estado de la colaboración: quién está en cada
 * sala y qué locks quedan sueltos cuando alguien se va.
 *
 * <p>La desconexión es el caso que más importa y el que más fácil se olvida. Cerrar la pestaña no
 * manda un {@code RELEASE_LOCK}: si nadie limpia, el elemento queda bloqueado hasta que venza el
 * TTL, y el resto de la sala se queda mirando el candado de alguien que ya no está. Con veinte
 * personas entrando y saliendo, esos cinco segundos de más se acumulan hasta volver la
 * herramienta molesta.
 */
@Component
public class SesionDeColaboracionListener {

    private static final Logger log =
            LoggerFactory.getLogger(SesionDeColaboracionListener.class);

    /** Sólo interesa la suscripción a la sala de un diagrama, no las de errores o locks. */
    private static final Pattern SALA =
            Pattern.compile("^/topic/diagrams/(?<diagramId>[^/]+)$");

    private final RoomManager roomManager;
    private final LockManagerService lockManager;

    public SesionDeColaboracionListener(
            RoomManager roomManager, LockManagerService lockManager) {
        this.roomManager = roomManager;
        this.lockManager = lockManager;
    }

    /**
     * Alta en la sala al suscribirse, no al conectarse: el CONNECT dice que el usuario abrió la
     * aplicación, el SUBSCRIBE dice qué diagrama está mirando.
     */
    @EventListener
    public void alSuscribirse(SessionSubscribeEvent evento) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(evento.getMessage());
        String destino = accessor.getDestination();
        if (destino == null) {
            return;
        }
        Matcher matcher = SALA.matcher(destino);
        if (!matcher.matches()) {
            return;
        }
        Participante quien = StompChannelInterceptor.participanteDe(accessor);
        if (quien == null) {
            return; // sin CONNECT autenticado no debería llegar acá
        }
        try {
            roomManager.entrar(
                    UUID.fromString(matcher.group("diagramId")),
                    accessor.getSessionId(),
                    quien);
        } catch (IllegalArgumentException e) {
            log.warn("SUBSCRIBE a {} con un diagramId que no es UUID; se ignora", destino);
        }
    }

    @EventListener
    public void alDesconectarse(SessionDisconnectEvent evento) {
        String sessionId = evento.getSessionId();
        int liberados = lockManager.liberarTodosDeLaSesion(sessionId);
        roomManager.salir(sessionId)
                .ifPresent(sala -> log.debug("Sesión {} salió de la sala {}", sessionId, sala));
        if (liberados > 0) {
            log.debug("Se liberaron {} lock(s) de la sesión {}", liberados, sessionId);
        }
    }
}
