package com.modelcollab.assistant.core;

/** Quién escribió un mensaje del chat grupal (CU19). Se guarda como texto en la base. */
public enum ChatAuthorType {
    /** Un miembro del proyecto. Su identidad sale siempre del Principal del JWT. */
    USER,
    /** La IA del equipo, que participa como un integrante más. */
    AI,
    /** El propio sistema: avisos como "La IA no está disponible ahora". */
    SYSTEM
}
