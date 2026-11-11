package com.modelcollab.assistant.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.ai.service.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elige el modelo del chat con las mismas reglas que {@code AiConfig} de CU16: sin clave,
 * simulado (y lo avisa al arrancar); con clave, el proveedor configurado.
 * Encapsula el cliente base dentro de un {@link AssistantChatModelRouter} para permitir
 * selección dinámica de IA local (Ollama) por proyecto (CU21).
 */
@Configuration
public class AiChatConfig {

    private static final Logger log = LoggerFactory.getLogger(AiChatConfig.class);

    @Bean
    public AiChatModelClient aiChatModelClient(
            AiProperties propiedades,
            ObjectMapper objectMapper,
            @Value("${modelcollab.ai.local-model:${OLLAMA_MODEL:qwen2.5-coder:3b}}") String localModel) {
        AiChatModelClient baseClient;
        if (!propiedades.hayClave()) {
            log.warn("Sin clave de IA: la IA del chat (CU19) arranca en MODO SIMULADO y responde "
                    + "\"Hola {nombre}, (respuesta simulada)\".");
            baseClient = new FakeChatClient();
        } else {
            baseClient = new ProveedorAiChatModelClient(propiedades, objectMapper);
            log.info("IA del chat (CU19) contra {}", baseClient.descripcion());
        }
        return new AssistantChatModelRouter(baseClient, objectMapper, localModel);
    }

    /** Sobrecarga para tests unitarios sin contexto Spring. */
    public AiChatModelClient aiChatModelClient(AiProperties propiedades, ObjectMapper objectMapper) {
        return aiChatModelClient(propiedades, objectMapper, null);
    }
}
