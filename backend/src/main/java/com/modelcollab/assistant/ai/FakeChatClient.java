package com.modelcollab.assistant.ai;

/**
 * Cliente simulado y determinista, para cuando no hay clave de IA y para los tests. Siempre
 * responde lo mismo, dirigiéndose a quien preguntó: {@code "Hola {nombre}, (respuesta
 * simulada)"}.
 */
public class FakeChatClient implements AiChatModelClient {

    public static final String MODELO = "simulado";

    @Override
    public ChatCompletion chat(ChatRequest request) {
        String nombre = request == null ? null : request.askerFirstName();
        String texto = nombre == null || nombre.isBlank()
                ? "Hola, (respuesta simulada)"
                : "Hola " + nombre.strip() + ", (respuesta simulada)";
        return new ChatCompletion(texto, MODELO);
    }

    @Override
    public String descripcion() {
        return "Chat simulado";
    }

    @Override
    public boolean esSimulado() {
        return true;
    }
}
