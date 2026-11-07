package com.modelcollab.assistant.chat;

/**
 * Estado de lectura del chat de un proyecto para una persona (CU23).
 *
 * @param lastReadSeq el último seq que esta persona marcó como leído; 0 si nunca.
 * @param unreadCount mensajes con seq mayor que {@code lastReadSeq} que no escribió ella
 *     (los de la IA y del sistema sí cuentan).
 * @param lastSeq el mayor seq con mensaje en el proyecto; 0 si no hay mensajes.
 */
public record ChatReadState(long lastReadSeq, long unreadCount, long lastSeq) {}
