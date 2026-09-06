package com.modelcollab.collaboration.dto;

import java.util.Optional;

/**
 * Catálogo formal de operaciones atómicas de §8.1, con su política de exclusión mutua.
 *
 * <p><b>Esta enumeración es la única fuente de verdad sobre qué operación exige lock.</b> La
 * política no se decide en un {@code if} del servicio ni en el cliente: si estuviera repartida,
 * el día que §8.1 cambie habría que encontrar todos los lugares, y el que se escape se convierte
 * en un agujero de exclusión mutua que sólo aparece con dos usuarios simultáneos.
 *
 * <p>{@link #implementada} distingue las operaciones que hoy se aplican de verdad al estado
 * canónico de las que el plan agenda para más adelante ({@code UPDATE_RELATIONSHIP} el Día 12,
 * {@code BULK_MERGE} el Día 16). Una operación no implementada se rechaza con un error explícito
 * en vez de aceptarse y perderse en silencio, que es la falla que deja al cliente creyendo que
 * guardó algo.
 */
public enum OperationType {

    // --- Motor de locks: no viajan a /mutate sino a /lock -----------------------------------
    ACQUIRE_LOCK(Exclusion.MOTOR_DE_LOCKS, false),
    RELEASE_LOCK(Exclusion.MOTOR_DE_LOCKS, false),

    // --- Estructura del diagrama -----------------------------------------------------------
    ADD_CLASS(Exclusion.NO_REQUIERE, true),
    MOVE_CLASS(Exclusion.LWW_LIBRE, true),
    RESIZE_CLASS(Exclusion.LWW_LIBRE, true),
    RENAME_CLASS(Exclusion.REQUIERE_LOCK, true),
    DELETE_CLASS(Exclusion.REQUIERE_LOCK, true),

    ADD_ATTRIBUTE(Exclusion.REQUIERE_LOCK, true),
    UPDATE_ATTRIBUTE(Exclusion.REQUIERE_LOCK, true),
    DELETE_ATTRIBUTE(Exclusion.REQUIERE_LOCK, true),

    ADD_METHOD(Exclusion.REQUIERE_LOCK, true),
    UPDATE_METHOD(Exclusion.REQUIERE_LOCK, true),
    DELETE_METHOD(Exclusion.REQUIERE_LOCK, true),

    ADD_RELATIONSHIP(Exclusion.NO_REQUIERE, true),
    UPDATE_WAYPOINTS(Exclusion.LWW_LIBRE, true),
    DELETE_RELATIONSHIP(Exclusion.REQUIERE_LOCK, true),

    ADD_PACKAGE(Exclusion.NO_REQUIERE, true),
    UPDATE_PACKAGE(Exclusion.REQUIERE_LOCK, true),
    DELETE_PACKAGE(Exclusion.REQUIERE_LOCK, true),

    // --- Agendadas por el plan para más adelante -------------------------------------------
    /** Día 12: modifica la relación sin destruirla, conservando waypoints e identidad. */
    UPDATE_RELATIONSHIP(Exclusion.REQUIERE_LOCK, false),
    /** Día 16: inserción atómica de un sub-grafo (visión / importación XMI). */
    BULK_MERGE(Exclusion.LOCK_DE_DIAGRAMA, false),
    /**
     * Canal volátil de presencia. Figura en el catálogo de §8.1 pero <b>no viaja por
     * {@code /mutate}</b>: tiene su propio destino {@code /app/diagram/{id}/cursor}, no se
     * persiste y no consume secuencia. Por eso {@code implementada = false}: el aplicador no
     * la conoce ni debe conocerla, y si llegara por la tubería de mutaciones —o encolada en un
     * lote offline— corresponde rechazarla.
     */
    USER_CURSOR(Exclusion.NO_REQUIERE, false);

    /** Política de exclusión mutua de la cuarta columna de §8.1. */
    public enum Exclusion {
        /** Se aplica sin más: no puede entrar en conflicto con otro usuario. */
        NO_REQUIERE,
        /** Sin lock a propósito, para que el arrastre sea fluido; gana el último en llegar. */
        LWW_LIBRE,
        /** El emisor debe poseer el soft-lock del elemento en el momento de aplicar. */
        REQUIERE_LOCK,
        /** Bloquea el diagrama entero durante la operación. */
        LOCK_DE_DIAGRAMA,
        /** La operación es del propio motor de locks y no muta el estado canónico. */
        MOTOR_DE_LOCKS
    }

    private final Exclusion exclusion;
    private final boolean implementada;

    OperationType(Exclusion exclusion, boolean implementada) {
        this.exclusion = exclusion;
        this.implementada = implementada;
    }

    public Exclusion exclusion() {
        return exclusion;
    }

    /** Si el aplicador sabe llevarla al estado canónico hoy. */
    public boolean implementada() {
        return implementada;
    }

    public boolean requiereLock() {
        return exclusion == Exclusion.REQUIERE_LOCK;
    }

    /** Vacío si el texto no es ninguna operación del catálogo; nunca lanza. */
    public static Optional<OperationType> desde(String valor) {
        if (valor == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(valor.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
