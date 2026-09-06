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

    private static User usuario(UUID id, String email) {
        User user = new User(email, "$2a$10$hash-irrelevante-para-este-test", "Ana Pérez");
        // El id lo asigna JPA al persistir; acá se inyecta porque el test no toca la base.
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
