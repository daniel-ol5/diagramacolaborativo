package com.modelcollab.assistant.core;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Límite de frecuencia por ventana deslizante: como máximo {@code max} eventos por clave en
 * cualquier intervalo de {@code window}. Lo usan el chat (20 mensajes por minuto por usuario) y
 * el orquestador (10 llamadas a la IA por minuto por proyecto).
 *
 * <p>Ventana deslizante y no por minuto calendario: con minutos fijos, 20 mensajes a las 10:00:59
 * y otros 20 a las 10:01:00 pasan los dos, o sea 40 en un segundo.
 *
 * <p>En memoria y por instancia, igual que el broker STOMP: con una sola instancia del backend
 * alcanza; si se escala horizontalmente, el límite pasa a ser por instancia.
 */
public class SlidingWindowRateLimiter<K> {

    private final int max;
    private final long windowMs;
    private final Clock reloj;
    private final Map<K, Deque<Long>> eventos = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(int max, Duration window, Clock reloj) {
        this.max = max;
        this.windowMs = window.toMillis();
        this.reloj = reloj;
    }

    /** Registra un evento y devuelve {@code true} si entra en el límite; si no, no lo cuenta. */
    public boolean tryAcquire(K clave) {
        long ahora = reloj.millis();
        boolean[] aceptado = {false};
        // compute es atómico por clave: dos mensajes simultáneos del mismo usuario no pueden
        // leer los dos "19" y entrar los dos como el número 20.
        eventos.compute(
                clave,
                (k, cola) -> {
                    Deque<Long> c = cola == null ? new ArrayDeque<>() : cola;
                    while (!c.isEmpty() && c.peekFirst() <= ahora - windowMs) {
                        c.pollFirst();
                    }
                    if (c.size() < max) {
                        c.addLast(ahora);
                        aceptado[0] = true;
                    }
                    // Devolver null borra la entrada: las claves inactivas no se acumulan.
                    return c.isEmpty() ? null : c;
                });
        return aceptado[0];
    }
}
