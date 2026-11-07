package com.modelcollab.assistant.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CU23: el escape del patrón LIKE, sin base de datos. */
class JpaChatStoreEscapeTest {

    @Test
    @DisplayName("%, _ y ! se escapan con !; la barra invertida y el resto quedan igual")
    void escapa() {
        assertThat(JpaChatStore.ESCAPE).isEqualTo('!');
        assertThat(JpaChatStore.escaparLike("100%")).isEqualTo("100!%");
        assertThat(JpaChatStore.escaparLike("fecha_alta")).isEqualTo("fecha!_alta");
        assertThat(JpaChatStore.escaparLike("hola!")).isEqualTo("hola!!");
        assertThat(JpaChatStore.escaparLike("C:\\temp")).isEqualTo("C:\\temp");
        assertThat(JpaChatStore.escaparLike("ñandú 'x'")).isEqualTo("ñandú 'x'");
        assertThat(JpaChatStore.escaparLike("%_!")).isEqualTo("!%!_!!");
    }
}
