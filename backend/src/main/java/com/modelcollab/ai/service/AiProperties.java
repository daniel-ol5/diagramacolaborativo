package com.modelcollab.ai.service;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del asistente.
 *
 * <p><b>La aplicación tiene que arrancar sin clave.</b> Con {@code api-key} vacío el sistema
 * levanta en modo simulado y CU16 se puede usar y defender entero: el guardarraíl, la validación
 * del contrato de salida y el mapeo a las operaciones de §8.1 son nuestros y no dependen de
 * ninguna red. Lo único que falta sin clave es la parte que nadie duda que funciona.
 *
 * <p>Soporta tanto <b>OpenAI</b> (por defecto) como <b>Gemini</b> según {@code provider} o la clave provista.
 *
 * <p>Lo que <b>no</b> está acá es el tope de 5 operaciones. Vive como constante en
 * {@link AiCommandService} a propósito: un tope duro que se puede subir por configuración no es
 * un tope duro, es un valor por defecto.
 */
@ConfigurationProperties(prefix = "modelcollab.ai")
public record AiProperties(
        String provider,
        String apiKey,
        String model,
        String modeloDeRespaldo,
        String endpoint,
        Integer timeoutMs) {

    public AiProperties {
        if (provider == null || provider.isBlank()) {
            if (apiKey != null && apiKey.startsWith("AIza")) {
                provider = "gemini";
            } else {
                provider = "openai";
            }
        } else {
            provider = provider.strip().toLowerCase(Locale.ROOT);
        }

        boolean isOpenAi = "openai".equalsIgnoreCase(provider);

        if (model == null || model.isBlank()) {
            model = isOpenAi ? "gpt-4o-mini" : "gemini-3-flash-preview";
        }
        if (modeloDeRespaldo == null || modeloDeRespaldo.isBlank()) {
            modeloDeRespaldo = isOpenAi ? "gpt-4o" : "gemini-flash-lite-latest";
        }
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = isOpenAi
                    ? "https://api.openai.com/v1"
                    : "https://generativelanguage.googleapis.com/v1beta";
        }
        timeoutMs = timeoutMs == null ? 30000 : timeoutMs;
    }

    public boolean hayClave() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean esOpenAi() {
        return "openai".equalsIgnoreCase(provider);
    }
}
