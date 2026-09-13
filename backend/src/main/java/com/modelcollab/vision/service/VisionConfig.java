package com.modelcollab.vision.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.ai.service.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elige el lector de pizarras al arrancar, con el mismo criterio que CU16: sin clave, simulado, y
 * la aplicación levanta igual.
 *
 * <p>Comparte {@code AiProperties} con CU16 a propósito — es la misma clave de Gemini y el mismo
 * modelo. Duplicar la configuración llevaría a que alguien ponga la clave en una sola de las dos
 * y no entienda por qué media funcionalidad sigue simulada.
 */
@Configuration
public class VisionConfig {

    private static final Logger log = LoggerFactory.getLogger(VisionConfig.class);

    @Bean
    public VisionModelClient visionModelClient(
            AiProperties propiedades, ObjectMapper objectMapper) {

        if (!propiedades.hayClave()) {
            log.warn("Sin GEMINI_API_KEY: la lectura de fotos de CU17 arranca SIMULADA. "
                    + "Devuelve un borrador fijo que no corresponde a la imagen, y la respuesta "
                    + "lo declara para que la interfaz lo muestre.");
            return new FakeVisionModelClient();
        }
        log.info("Lectura de fotos de CU17 contra Gemini multimodal ({})", propiedades.model());
        return new GeminiVisionModelClient(propiedades, objectMapper);
    }
}
