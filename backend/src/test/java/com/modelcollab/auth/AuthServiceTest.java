package com.modelcollab.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelcollab.auth.dto.AuthResponse;
import com.modelcollab.auth.dto.LoginRequest;
import com.modelcollab.auth.dto.RegisterRequest;
import com.modelcollab.auth.model.User;
import com.modelcollab.auth.repository.UserRepository;
import com.modelcollab.auth.service.AuthService;
import com.modelcollab.auth.service.JwtService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Contrato de {@code /api/v1/auth/**} (D-11), verificado sin base de datos: el repositorio va
 * mockeado y el encoder es el real, porque el hasheo es justamente lo que se quiere comprobar.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String CONTRASENA = "contrasena-secreta";

    @Mock private UserRepository userRepository;
    @Mock private JwtService jwtService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private AuthService authService;

    @BeforeEach
    void prepararServicio() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService);
        lenient().when(jwtService.generate(any())).thenReturn("token-firmado");
    }

    @Test
    @DisplayName("El registro guarda la contraseña hasheada, nunca en claro")
    void registroHashea() {
        when(userRepository.existsByEmail("ana@modelcollab.dev")).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> conId(inv.getArgument(0)));

        AuthResponse respuesta =
                authService.register(
                        new RegisterRequest("ana@modelcollab.dev", CONTRASENA, "Ana Pérez"));

        ArgumentCaptor<User> guardado = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(guardado.capture());
        assertThat(guardado.getValue().getPasswordHash())
                .as("la contraseña en claro jamás debe llegar a la base")
                .isNotEqualTo(CONTRASENA)
                .startsWith("$2");
        assertThat(passwordEncoder.matches(CONTRASENA, guardado.getValue().getPasswordHash()))
                .isTrue();

        assertThat(respuesta.token()).isEqualTo("token-firmado");
        assertThat(respuesta.tokenType()).isEqualTo("Bearer");
        assertThat(respuesta.email()).isEqualTo("ana@modelcollab.dev");
        assertThat(respuesta.fullName()).isEqualTo("Ana Pérez");
        assertThat(respuesta.userId()).isNotNull();
    }

    @Test
    @DisplayName("El email se normaliza a minúsculas: el UNIQUE de Postgres sí distingue")
    void registroNormalizaElEmail() {
        when(userRepository.existsByEmail("ana@modelcollab.dev")).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> conId(inv.getArgument(0)));

        authService.register(
                new RegisterRequest("  Ana@ModelCollab.DEV  ", CONTRASENA, "Ana Pérez"));

        ArgumentCaptor<User> guardado = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(guardado.capture());
        assertThat(guardado.getValue().getEmail()).isEqualTo("ana@modelcollab.dev");
    }

    @Test
    @DisplayName("Registrar un email ya existente responde 409 y no guarda nada")
    void registroDuplicado() {
        when(userRepository.existsByEmail("ana@modelcollab.dev")).thenReturn(true);

        assertThatThrownBy(
                        () ->
                                authService.register(
                                        new RegisterRequest(
                                                "ana@modelcollab.dev", CONTRASENA, "Ana Pérez")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("El login correcto devuelve el token y los datos del usuario")
    void loginCorrecto() {
        User user = usuarioRegistrado();
        when(userRepository.findByEmail("ana@modelcollab.dev")).thenReturn(Optional.of(user));

        AuthResponse respuesta =
                authService.login(new LoginRequest("ana@modelcollab.dev", CONTRASENA));

        assertThat(respuesta.token()).isEqualTo("token-firmado");
        assertThat(respuesta.userId()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("Contraseña incorrecta responde 401 y no emite token")
    void loginContrasenaIncorrecta() {
        when(userRepository.findByEmail("ana@modelcollab.dev"))
                .thenReturn(Optional.of(usuarioRegistrado()));

        assertThatThrownBy(
                        () ->
                                authService.login(
                                        new LoginRequest("ana@modelcollab.dev", "otra-cosa")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(jwtService, never()).generate(any());
    }

    @Test
    @DisplayName("Email inexistente responde 401 con el mismo mensaje: el login no es un oráculo")
    void loginEmailInexistente() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () -> authService.login(new LoginRequest("nadie@ejemplo.com", CONTRASENA)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Credenciales inválidas");
    }

    private User usuarioRegistrado() {
        return conId(
                new User("ana@modelcollab.dev", passwordEncoder.encode(CONTRASENA), "Ana Pérez"));
    }

    /** Simula lo que hace JPA al persistir: asignar el id generado. */
    private static User conId(User user) {
        if (user.getId() == null) {
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        }
        return user;
    }
}
