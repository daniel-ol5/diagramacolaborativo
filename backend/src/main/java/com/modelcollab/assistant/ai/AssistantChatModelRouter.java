package com.modelcollab.assistant.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.ai.service.AiProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enrutador dinámico de clientes de chat IA (CU21).
 *
 * <p>Permite conmutar transparentemente entre el cliente en la nube (OpenAI / Gemini)
 * y una instancia local de Ollama según los {@link AssistantSettings} del proyecto.
 *
 * <p>Reutiliza {@link ProveedorAiChatModelClient} para ambos casos, ya que Ollama expone
 * una API compatible con OpenAI en {@code /v1/chat/completions}.
 */
public class AssistantChatModelRouter implements AiChatModelClient {

    private static final Logger log = LoggerFactory.getLogger(AssistantChatModelRouter.class);

    public static final String DEFAULT_LOCAL_MODEL = "qwen2.5-coder:3b";
    public static final int TIMEOUT_LOCAL_MS = 60000;

    private final AiChatModelClient cloudClient;
    private final ObjectMapper objectMapper;
    private final String localModel;
    private final Map<String, AiChatModelClient> cacheLocales = new ConcurrentHashMap<>();

    public AssistantChatModelRouter(
            AiChatModelClient cloudClient,
            ObjectMapper objectMapper,
            String localModel) {
        this.cloudClient = cloudClient;
        this.objectMapper = objectMapper;
        this.localModel = (localModel == null || localModel.isBlank()) ? DEFAULT_LOCAL_MODEL : localModel.trim();
    }

    /**
     * Resuelve el cliente de chat apropiado para los ajustes de un proyecto.
     *
     * @param settings ajustes del proyecto; si es nulo o CLOUD, retorna el cliente de la nube.
     * @return cliente configurado para el proveedor correspondiente.
     */
    public AiChatModelClient clientePara(AssistantSettings settings) {
        if (settings != null && settings.provider() == AssistantProvider.LOCAL) {
            String endpoint = normalizarEndpointLocal(settings.localEndpoint());
            return cacheLocales.computeIfAbsent(endpoint, ep -> {
                log.info("Creando cliente de chat local contra Ollama ({}) en {}", localModel, ep);
                AiProperties props = new AiProperties(
                        "openai",
                        "ollama",
                        localModel,
                        null,
                        ep,
                        TIMEOUT_LOCAL_MS);
                return new ProveedorAiChatModelClient(props, objectMapper);
            });
        }
        return cloudClient;
    }

    public static String normalizarEndpointLocal(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "http://localhost:11434/v1";
        }
        String ep = endpoint.trim();
        while (ep.endsWith("/")) {
            ep = ep.substring(0, ep.length() - 1);
        }
        if (!ep.endsWith("/v1")) {
            ep = ep + "/v1";
        }
        return ep;
    }

    public String localModel() {
        return localModel;
    }

    public AiChatModelClient cloudClient() {
        return cloudClient;
    }

    // ------------------------------------------------ Delegación por defecto (AiChatModelClient)

    @Override
    public ChatCompletion chat(ChatRequest request) {
        return cloudClient.chat(request);
    }

    @Override
    public String descripcion() {
        return cloudClient.descripcion();
    }

    @Override
    public boolean esSimulado() {
        return cloudClient.esSimulado();
    }
}

