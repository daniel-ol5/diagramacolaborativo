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
public record AiProperties(
        String apiKey, String model, String modeloDeRespaldo, String endpoint, Integer timeoutMs) {

    public AiProperties {
        model = model == null || model.isBlank() ? "gemini-3-flash-preview" : model;
        // EL MODELO DE RESPALDO, y por que existe.
        //
        // Medido el 20/09 intercalando llamadas a cuatro modelos con la misma clave, en el mismo
        // minuto: gemini-flash-latest devolvio 429 las CUATRO veces, y gemini-3-flash-preview y
        // gemini-flash-lite-latest devolvieron 200 las cuatro. La saturacion y la cuota son POR
        // MODELO, no de la cuenta ni de la red.
        //
        // Eso es lo que ningun reintento puede arreglar: si el modelo esta agotado, insistir
        // contra el mismo modelo falla igual por mas que se espere. Cambiar de modelo si
        // contesta, y contesta en el primer intento.
        //
        // Se elige el preview y no el lite porque el lite tardo entre 9 y 18 s contra 1,3 a 8 s
        // del preview, y esto corre con una persona esperando a que su comando dictado haga algo.
        modeloDeRespaldo = modeloDeRespaldo == null || modeloDeRespaldo.isBlank()
                ? "gemini-flash-lite-latest"
                : modeloDeRespaldo;
        endpoint = endpoint == null || endpoint.isBlank()
                ? "https://generativelanguage.googleapis.com/v1beta"
                : endpoint;
        timeoutMs = timeoutMs == null ? 30000 : timeoutMs;
    }

    public boolean hayClave() {
        return apiKey != null && !apiKey.isBlank();
    }
}
