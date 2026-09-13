package com.modelcollab.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modelcollab.auth.model.User;
import com.modelcollab.auth.service.JwtService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Unidad pura: no levanta contexto de Spring ni necesita base de datos. */
class JwtServiceTest {

    private static final String SECRETO =
            "modelcollab-secreto-de-prueba-hs256-con-mas-de-32-bytes-de-largo";
    private static final long OCHO_HORAS_MS = 28_800_000L;

    private final JwtService jwtService = new JwtService(SECRETO, OCHO_HORAS_MS);

    @Test
    @DisplayName("Un token emitido para un usuario se parsea de vuelta a su userId")
    void idaYVuelta() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generate(usuario(userId, "ana@modelcollab.dev"));

        assertThat(jwtService.parse(token)).isEqualTo(userId);
    }

    @Test
    @DisplayName("Un token firmado con otra clave se rechaza")
    void firmaAjena() {
        JwtService otroServidor =
                new JwtService(
                        "otro-secreto-completamente-distinto-de-mas-de-32-bytes", OCHO_HORAS_MS);
        String token = otroServidor.generate(usuario(UUID.randomUUID(), "mallory@ejemplo.com"));

        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Un token alterado se rechaza: la firma no cubre la modificación")
    void tokenAlterado() {
        String token = jwtService.generate(usuario(UUID.randomUUID(), "ana@modelcollab.dev"));
        // Se cambia un carácter del payload, que viaja en Base64 y es perfectamente legible.
        // Que esto falle es exactamente lo que compra la firma: el token es público, no secreto.
        String[] partes = token.split("\\.");
        String alterado = partes[0] + "." + partes[1].substring(1) + "X." + partes[2];

        assertThatThrownBy(() -> jwtService.parse(alterado)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Un token vencido se rechaza aunque la firma sea válida")
    void tokenVencido() {
        // Expiración negativa: nace vencido, sin tener que dormir el test.
        JwtService yaVencido = new JwtService(SECRETO, -1000L);
        String token = yaVencido.generate(usuario(UUID.randomUUID(), "ana@modelcollab.dev"));

        assertThatThrownBy(() -> jwtService.parse(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("Un secreto de menos de 32 bytes rompe el arranque, no el primer login")
    void secretoCorto() {
        assertThatThrownBy(() -> new JwtService("corto", OCHO_HORAS_MS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("SIN secreto la aplicación no arranca: antes arrancaba con una clave pública")
    void sinSecretoNoArranca() {
        // Este es el fallo que habia que volver ruidoso. Con un valor por defecto, un
        // despliegue que se olvidara de inyectar JWT_SECRET levantaba perfecto y firmaba con
        // una clave publicada en el repositorio: cualquiera que la leyera podia fabricarse un
        // token valido para cualquier usuario. No habia error ni aviso — funcionaba.
        //
        // Es lo contrario de la contrasenia de la base, que si conserva su valor por defecto:
        // esa falla CERRADA (no conecta y se nota), y esta fallaba ABIERTA.
        for (String vacio : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> new JwtService(vacio, OCHO_HORAS_MS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET");
        }
    }

    @Test
    @DisplayName("El secreto de desarrollo publicado se rechaza aunque lo pongan a mano")
    void secretoQuemado() {
        // El camino que cierra: alguien clona el repositorio, ve el fallo al arrancar, busca en
        // el historial cual era el valor por defecto y lo exporta tal cual. El mensaje tiene
        // que explicarle por que eso no sirve, no repetirle que falta la variable.
        assertThatThrownBy(() -> new JwtService(
                "modelcollab-desarrollo-local-clave-hs256-no-usar-en-produccion-64b",
                OCHO_HORAS_MS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publicado en el repositorio");
    }

    @Test
    @DisplayName("El mensaje dice cómo generar uno, en vez de dejar al que despliega buscando")
    void elMensajeEsAccionable() {
        // Un error que dice "falta la variable" y nada mas produce un secreto elegido a mano,
        // que es como aparece "cambiar123" en produccion.
        assertThatThrownBy(() -> new JwtService("", OCHO_HORAS_MS))
                .hasMessageContaining("openssl rand");
    }

    private static User usuario(UUID id, String email) {
        User user = new User(email, "$2a$10$hash-irrelevante-para-este-test", "Ana Pérez");
        // El id lo asigna JPA al persistir; acá se inyecta porque el test no toca la base.
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
