package com.modelcollab.assistant.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.ai.service.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elige el modelo del chat con las mismas reglas que {@code AiConfig} de CU16: sin clave,
 * simulado (y lo avisa al arrancar); con clave, el proveedor configurado. {@code AiProperties}
 * ya lo registra {@code AiConfig}, no se vuelve a habilitar acá.
 */
@Configuration
public class AiChatConfig {

    private static final Logger log = LoggerFactory.getLogger(AiChatConfig.class);

    @Bean
    public AiChatModelClient aiChatModelClient(AiProperties propiedades, ObjectMapper objectMapper) {
        if (!propiedades.hayClave()) {
            log.warn("Sin clave de IA: la IA del chat (CU19) arranca en MODO SIMULADO y responde "
                    + "\"Hola {nombre}, (respuesta simulada)\".");
            return new FakeChatClient();
        }
        AiChatModelClient cliente = new ProveedorAiChatModelClient(propiedades, objectMapper);
        log.info("IA del chat (CU19) contra {}", cliente.descripcion());
        return cliente;
    }
}
