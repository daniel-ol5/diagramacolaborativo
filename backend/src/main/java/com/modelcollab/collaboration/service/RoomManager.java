package com.modelcollab.collaboration.service;

import com.modelcollab.collaboration.dto.Participante;
import com.modelcollab.collaboration.dto.PresenceMessage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Quién está en cada sala, y avisarle a la sala cuando eso cambia.
 *
 * <p>La sala se registra en el SUBSCRIBE y no en el CONNECT porque son cosas distintas: el
 * CONNECT dice "este usuario abrió la aplicación", el SUBSCRIBE dice "está mirando <b>este</b>
 * diagrama". Un usuario con tres pestañas en tres diagramas tiene tres sesiones, y es la
 * suscripción la que dice cuál es cuál.
 *
 * <p>Estado en memoria, igual que los locks y por el mismo motivo: la presencia describe quién
 * está conectado <b>ahora</b>, y sobrevivir a un reinicio sería incorrecto, no útil.
 */
@Service
public class RoomManager {

    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

    /** Canal privado por el que se responde a un pedido explícito de roster. */
    public static final String COLA_PRESENCIA = "/queue/presence";

    private final Map<UUID, Map<String, Participante>> salas = new ConcurrentHashMap<>();
    /** Índice inverso: al desconectarse sólo se conoce la sesión, no la sala. */
    private final Map<String, UUID> salaDeLaSesion = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate messagingTemplate;

    public RoomManager(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void entrar(UUID diagramId, String sessionId, Participante quien) {
        salas.computeIfAbsent(diagramId, k -> new ConcurrentHashMap<>()).put(sessionId, quien);
        salaDeLaSesion.put(sessionId, diagramId);

        List<Participante> presentes = participantes(diagramId);
        log.debug("{} entró a la sala {} ({} presentes)",
                quien.userName(), diagramId, presentes.size());

        difundir(diagramId, presentes);
    }

    /**
     * Responde el roster a un cliente que lo pide, por su canal privado.
     *
     * <p>Existe porque <b>el recién llegado no puede confiar en la difusión que lo incluye</b>:
     * el evento de suscripción y el registro de la suscripción en el broker viajan por caminos
     * distintos, así que hay una ventana en la que el servidor ya lo cuenta como presente pero
     * el broker todavía no le entrega nada. Es justo el peor caso, porque es el único que no
     * sabe quién está.
     *
     * <p>Empujárselo automáticamente al entrar <b>no resuelve nada</b>: su canal privado tiene la
     * misma carrera de registro que el topic. La única forma sin condiciones de carrera es que
     * el cliente pregunte cuando ya terminó de suscribirse a todo, que es lo que hace este
     * destino. A partir de ahí, las altas y bajas ajenas llegan solas por el topic.
     */
    public void responderRoster(UUID diagramId, UUID aQuien) {
        messagingTemplate.convertAndSendToUser(
                aQuien.toString(), COLA_PRESENCIA, PresenceMessage.de(participantes(diagramId)));
    }

    /** Devuelve la sala que abandonó, o vacío si esa sesión no estaba en ninguna. */
    public Optional<UUID> salir(String sessionId) {
        UUID diagramId = salaDeLaSesion.remove(sessionId);
        if (diagramId == null) {
            return Optional.empty();
        }
        // computeIfPresent para no dejar salas vacías colgando: con sesiones entrando y saliendo
        // todo el día, un mapa que sólo crece es una fuga de memoria lenta.
        salas.computeIfPresent(diagramId, (k, ocupantes) -> {
            ocupantes.remove(sessionId);
            return ocupantes.isEmpty() ? null : ocupantes;
        });

        // Si la sala quedó vacía no hay a quién avisarle, y difundir a un topic sin suscriptores
        // es trabajo puro para nada.
        List<Participante> presentes = participantes(diagramId);
        if (!presentes.isEmpty()) {
            difundir(diagramId, presentes);
        }
        return Optional.of(diagramId);
    }

    public List<Participante> participantes(UUID diagramId) {
        Map<String, Participante> ocupantes = salas.get(diagramId);
        if (ocupantes == null) {
            return List.of();
        }
        // Distinct por usuario: dos pestañas del mismo colaborador son dos sesiones pero una
        // sola persona, y la sala no tiene por qué dibujarlo dos veces.
        return ocupantes.values().stream().distinct().toList();
    }

    public int salasActivas() {
        return salas.size();
    }

    private void difundir(UUID diagramId, List<Participante> presentes) {
        messagingTemplate.convertAndSend(
                "/topic/diagrams/" + diagramId, PresenceMessage.de(presentes));
    }
}
