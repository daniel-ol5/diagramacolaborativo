package com.modelcollab.assistant.core;

/**
 * Se entera de cada mensaje nuevo de un USER, ya guardado y difundido. Es el gancho del
 * orquestador de la IA: {@code ChatService} no sabe nada de la IA, sólo avisa.
 *
 * <p>Se llama en el hilo de quien escribió (el de STOMP o el de la petición REST), así que una
 * implementación que haga algo lento tiene que delegarlo a otro hilo.
 */
public interface UserMessageListener {

    void onUserMessage(ChatMessage message);
}
