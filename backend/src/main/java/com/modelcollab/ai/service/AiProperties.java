package com.modelcollab.ai.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del asistente.
 *
 * <p><b>La aplicación tiene que arrancar sin clave.</b> Con {@code api-key} vacío el sistema
 * levanta en modo simulado y CU16 se puede usar y defender entero: el guardarraíl, la validación
 * del contrato de salida y el mapeo a las operaciones de §8.1 son nuestros y no dependen de
 * ninguna red. Lo único que falta sin clave es la parte que nadie duda que funciona.
 *
 * <p>Lo que <b>no</b> está acá es el tope de 5 operaciones. Vive como constante en
 * {@link AiCommandService} a propósito: un tope duro que se puede subir por configuración no es
 * un tope duro, es un valor por defecto.
 */
@ConfigurationProperties(prefix = "modelcollab.ai")
public record AiProperties(String apiKey, String model, String endpoint, Integer timeoutMs) {

    public AiProperties {
        model = model == null || model.isBlank() ? "gemini-flash-latest" : model;
        endpoint = endpoint == null || endpoint.isBlank()
                ? "https://generativelanguage.googleapis.com/v1beta"
                : endpoint;
        timeoutMs = timeoutMs == null ? 30000 : timeoutMs;
    }

    public boolean hayClave() {
        return apiKey != null && !apiKey.isBlank();
    }
}
