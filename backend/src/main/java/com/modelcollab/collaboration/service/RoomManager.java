package com.modelcollab.collaboration.service;

import com.modelcollab.collaboration.dto.Participante;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Quién está en cada sala. Alta al suscribirse a {@code /topic/diagrams/{id}}, baja al
 * desconectarse.
 *
 * <p>La sala se registra en el SUBSCRIBE y no en el CONNECT porque son cosas distintas: el
 * CONNECT dice "este usuario abrió la aplicación", el SUBSCRIBE dice "está mirando <b>este</b>
 * diagrama". Un usuario con tres pestañas abiertas en tres diagramas tiene tres sesiones, y es la
 * suscripción la que dice cuál es cuál.
 *
 * <p>Estado en memoria, igual que los locks y por el mismo motivo: la presencia describe quién
 * está conectado <b>ahora</b>, y sobrevivir a un reinicio sería incorrecto, no útil.
 *
 * <p>Hoy es contabilidad: quién está y en qué sala. Los cursores y la difusión de presencia son
 * del Día 9.
 */
@Service
public class RoomManager {

    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

    private final Map<UUID, Map<String, Participante>> salas = new ConcurrentHashMap<>();
    /** Índice inverso: al desconectarse sólo se conoce la sesión, no la sala. */
    private final Map<String, UUID> salaDeLaSesion = new ConcurrentHashMap<>();

    public void entrar(UUID diagramId, String sessionId, Participante quien) {
        salas.computeIfAbsent(diagramId, k -> new ConcurrentHashMap<>()).put(sessionId, quien);
        salaDeLaSesion.put(sessionId, diagramId);
        log.debug("{} entró a la sala {} ({} presentes)",
                quien.nombre(), diagramId, salas.get(diagramId).size());
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
        return Optional.of(diagramId);
    }

    public Collection<Participante> participantes(UUID diagramId) {
        Map<String, Participante> ocupantes = salas.get(diagramId);
        return ocupantes == null ? List.of() : List.copyOf(ocupantes.values());
    }

    public int salasActivas() {
        return salas.size();
    }
}
