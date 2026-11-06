package com.modelcollab.assistant.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.metamodel.model.CanonicalModel;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lee {@code current_state} con el ObjectMapper canónico, como {@code GET /snapshot}, pero sin
 * lanzar: cualquier problema da {@link Optional#empty()}.
 *
 * <p>Vuelve a comprobar que el diagrama sea del proyecto aunque ChatService ya lo validó al
 * guardar el mensaje: la carga no se fía de esa validación previa.
 *
 * <p>El log lleva solo el motivo y el tipo de excepción: ni ids completos ni contenido.
 */
@Component
public class JpaDiagramContextSource implements DiagramContextSource {

    private static final Logger log = LoggerFactory.getLogger(JpaDiagramContextSource.class);

    private final DiagramRepository diagramas;
    private final ObjectMapper mapper;

    public JpaDiagramContextSource(DiagramRepository diagramas, ObjectMapper mapper) {
        this.diagramas = diagramas;
        this.mapper = mapper;
    }

    /**
     * Sin {@code @Transactional} propio: si el repositorio fallara dentro de una transacción
     * nuestra, ésta quedaría marcada rollback-only y el commit lanzaría fuera del catch. El id del
     * proyecto se lee del proxy LAZY sin inicializarlo, y {@code current_state} es una columna
     * básica que viene con la entidad.
     */
    @Override
    public Optional<CanonicalModel> cargar(UUID diagramId, UUID projectId) {
        if (diagramId == null || projectId == null) {
            return Optional.empty();
        }
        try {
            Optional<Diagram> encontrado = diagramas.findById(diagramId);
            if (encontrado.isEmpty()) {
                log.info("Chat: el diagrama del mensaje no existe; se responde sin resumen");
                return Optional.empty();
            }
            Diagram diagrama = encontrado.get();
            if (diagrama.getProject() == null || !projectId.equals(diagrama.getProject().getId())) {
                log.warn("Chat: el diagrama del mensaje no es del proyecto; se responde sin resumen");
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(diagrama.getCurrentState(), CanonicalModel.class));
        } catch (Exception e) {
            log.warn("Chat: no se pudo leer el diagrama; se responde sin resumen ({})",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
