package com.modelcollab.assistant.core;

/**
 * Qué clase de mensaje es. CU19 usa {@code CHAT}, {@code ANSWER} y {@code SYSTEM}; {@code REVIEW}
 * y {@code SUMMARY} quedan reservados para los casos de uso que comparten este chat.
 */
public enum ChatMessageKind {
    CHAT,
    ANSWER,
    REVIEW,
    SUMMARY,
    SYSTEM
}
