package com.modelcollab.diagram.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.diagram.dto.ValidationReportDTO;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.MetamodelValidator;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CU13: valida un diagrama guardado antes de generar código a partir de él.
 *
 * <p>Es la capa fina que va de "un id de diagrama" a "el informe": lee el JSONB, lo deserializa
 * con el mapper canónico y se lo pasa al {@link MetamodelValidator}, que es quien tiene las
 * reglas y no sabe nada de bases de datos ni de HTTP.
 */
@Service
public class PreGenerationValidatorService {

    private final DiagramRepository diagramRepository;
    private final MetamodelValidator validator;
    private final ObjectMapper objectMapper;

    public PreGenerationValidatorService(
            DiagramRepository diagramRepository,
            MetamodelValidator validator,
            ObjectMapper objectMapper) {
        this.diagramRepository = diagramRepository;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ValidationReportDTO validar(UUID diagramId) {
        return validator.validar(estadoDe(diagramId));
    }

    /** El estado canónico del diagrama, para que el generador no lo vuelva a leer. */
    @Transactional(readOnly = true)
    public CanonicalModel estadoDe(UUID diagramId) {
        Diagram diagrama = diagramRepository.findById(diagramId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "El diagrama no existe"));
        try {
            return objectMapper.readValue(diagrama.getCurrentState(), CanonicalModel.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "El current_state almacenado no es un modelo canónico válido", e);
        }
    }
}
