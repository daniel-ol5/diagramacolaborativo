package com.modelcollab.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elige el cliente de IA al arrancar según haya clave o no.
 *
 * <h2>Sin clave la aplicación arranca igual</h2>
 *
 * <p>Esto es un requisito y no una comodidad. Si la falta de {@code GEMINI_API_KEY} impidiera
 * levantar el contexto, el backend entero quedaría rehén de un servicio de terceros: no se
 * podrían correr los tests de integración, no se podría desarrollar el lienzo, no se podría
 * desplegar. Un sistema que no arranca sin una clave de un tercero no es un sistema desplegable.
 *
 * <p>Sin clave se elige {@link FakeAiModelClient} y CU16 funciona igual, con sus límites. El
 * arranque lo dice con todas las letras: una degradación silenciosa sería peor que el fallo,
 * porque alguien podría demostrar el caso de uso creyendo que habla con Gemini.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /**
     * Un solo bean de {@link AiModelClient}, decidido acá y no con dos {@code @Component} y una
     * anotación condicional: así el que lee el código encuentra la decisión en un lugar, y no
     * hay forma de que queden los dos registrados y Spring elija uno por orden de escaneo.
     */
    @Bean
    public AiModelClient aiModelClient(
            AiProperties propiedades, CommandGuardrail guardarrail, ObjectMapper objectMapper) {

        if (!propiedades.hayClave()) {
            log.warn("Sin GEMINI_API_KEY: el asistente de CU16 arranca en MODO SIMULADO. "
                    + "El guardarraíl, la validación del contrato y el mapeo a operaciones "
                    + "funcionan igual; la interpretación es por reglas locales.");
            return new FakeAiModelClient(guardarrail, objectMapper);
        }
        log.info("Asistente de CU16 contra Gemini ({})", propiedades.model());
        return new GeminiAiModelClient(propiedades, objectMapper);
    }
}
