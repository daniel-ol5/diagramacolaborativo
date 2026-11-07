package com.modelcollab.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CU19, etapa 4, tanda 2: AddressingGuard. */
class AddressingGuardTest {

    @Test
    @DisplayName("Nombre presente en los primeros 80 caracteres: no se toca")
    void presente() {
        assertThat(AddressingGuard.apply("Ana, una composición es...", "Ana")).isEqualTo("Ana, una composición es...");
        assertThat(AddressingGuard.apply("Claro, Ana: es una relación fuerte.", "Ana"))
                .isEqualTo("Claro, Ana: es una relación fuerte.");
    }

    @Test
    @DisplayName("Nombre ausente: se antepone \"{nombre}, \"")
    void ausente() {
        assertThat(AddressingGuard.apply("Una composición es una relación fuerte.", "Ana"))
                .isEqualTo("Ana, Una composición es una relación fuerte.");
        assertThat(AddressingGuard.apply("  con espacios", "Ana")).isEqualTo("Ana, con espacios");
    }

    @Test
    @DisplayName("Sin distinguir acentos")
    void acentos() {
        assertThat(AddressingGuard.apply("Hola andres, mira esto", "Andrés")).isEqualTo("Hola andres, mira esto");
        assertThat(AddressingGuard.apply("José, sí", "Jose")).isEqualTo("José, sí");
    }

    @Test
    @DisplayName("Sin distinguir mayúsculas")
    void mayusculas() {
        assertThat(AddressingGuard.apply("ANA, mira", "Ana")).isEqualTo("ANA, mira");
        assertThat(AddressingGuard.apply("ok ana", "ANA")).isEqualTo("ok ana");
    }

    @Test
    @DisplayName("Más allá de los 80 caracteres no cuenta; dentro de otra palabra tampoco")
    void ventanaYPalabraCompleta() {
        String largo = "x".repeat(AddressingGuard.VENTANA) + " Ana";
        assertThat(AddressingGuard.apply(largo, "Ana")).isEqualTo("Ana, " + largo);
        assertThat(AddressingGuard.apply("Mañana lo vemos", "Ana")).isEqualTo("Ana, Mañana lo vemos");
    }

    @Test
    @DisplayName("Sin nombre de pila (o respuesta nula): no se toca")
    void sinNombre() {
        assertThat(AddressingGuard.apply("Hola", "")).isEqualTo("Hola");
        assertThat(AddressingGuard.apply("Hola", "  ")).isEqualTo("Hola");
        assertThat(AddressingGuard.apply("Hola", null)).isEqualTo("Hola");
        assertThat(AddressingGuard.apply(null, "Ana")).isNull();
    }

    @Test
    @DisplayName("nombreDePila: primera palabra, sin corchetes")
    void nombreDePila() {
        assertThat(AddressingGuard.nombreDePila("Ana María Pérez")).isEqualTo("Ana");
        assertThat(AddressingGuard.nombreDePila("[Eve] Mallory")).isEqualTo("Eve");
        assertThat(AddressingGuard.nombreDePila("   ")).isEmpty();
        assertThat(AddressingGuard.nombreDePila(null)).isEmpty();
    }
}
