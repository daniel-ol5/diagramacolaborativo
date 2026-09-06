package com.modelcollab.auth.service;

import com.modelcollab.auth.dto.AuthResponse;
import com.modelcollab.auth.dto.LoginRequest;
import com.modelcollab.auth.dto.RegisterRequest;
import com.modelcollab.auth.model.User;
import com.modelcollab.auth.repository.UserRepository;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Registro y login. Emite el JWT que después usan tanto REST como el CONNECT de STOMP. */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest peticion) {
        String email = normalizar(peticion.email());
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El email ya está registrado");
        }
        User user =
                new User(email, passwordEncoder.encode(peticion.password()), peticion.fullName());
        // Se usa la instancia devuelta y no la local: es la que trae el id generado.
        User guardado = userRepository.save(user);
        return AuthResponse.de(jwtService.generate(guardado), guardado);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest peticion) {
        Optional<User> encontrado = userRepository.findByEmail(normalizar(peticion.email()));

        // Se verifica el hash aunque el usuario no exista, contra un encoder de descarte, para
        // que el tiempo de respuesta sea el mismo en los dos casos. Sin esto, un atacante mide
        // la latencia y descubre qué emails están registrados sin necesitar la contraseña.
        String hash = encontrado.map(User::getPasswordHash).orElse(HASH_INEXISTENTE);
        boolean coincide = passwordEncoder.matches(peticion.password(), hash);

        if (encontrado.isEmpty() || !coincide) {
            // Un solo mensaje para "no existe" y para "contraseña incorrecta": distinguirlos
            // convierte al login en un oráculo de qué emails tienen cuenta.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }
        User user = encontrado.get();
        return AuthResponse.de(jwtService.generate(user), user);
    }

    /**
     * BCrypt de una contraseña aleatoria que nadie conoce. Sirve sólo para quemar los mismos
     * milisegundos que costaría verificar un usuario real.
     */
    private static final String HASH_INEXISTENTE =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /**
     * Los emails se guardan y se buscan en minúsculas: el UNIQUE de PostgreSQL distingue
     * mayúsculas, así que sin normalizar, {@code Ana@x.com} y {@code ana@x.com} serían dos
     * cuentas distintas y el usuario no entendería por qué no puede entrar.
     */
    private static String normalizar(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
