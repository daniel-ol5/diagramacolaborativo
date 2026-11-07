package com.modelcollab.assistant.ai;

/**
 * Modelo de lenguaje para conversar en el chat del proyecto (CU19), de varios turnos.
 *
 * <p>Es independiente de {@code AiModelClient} de CU16 a propósito: aquél traduce UNA orden a un
 * plan JSON con temperatura 0; éste conversa en texto libre. Comparten la configuración
 * ({@code AiProperties}), el modelo de respaldo y los reintentos.
 */
public interface AiChatModelClient {

    /**
     * @throws AiUnavailableException si el modelo no pudo responder, por el motivo que sea.
     */
    ChatCompletion chat(ChatRequest request);

    /** Para logs: proveedor y modelo, nunca la clave. */
    String descripcion();

    /** true si responde el cliente simulado, sin modelo real detrás. */
    boolean esSimulado();
}
