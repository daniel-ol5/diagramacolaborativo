package com.modelcollab.config;

import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Seguridad HTTP. Stateless: no hay sesión de servlet, el JWT es el único portador de
 * identidad, y por eso mismo CSRF va desactivado (sin cookie de sesión no hay nada que un
 * sitio de terceros pueda hacer viajar automáticamente).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                // Los despachos internos del contenedor NO se autorizan como si
                                // fueran peticiones del cliente. Sin esto, Spring reenvía cada
                                // error a /error, la cadena vuelve a evaluar ese reenvío, /error
                                // no está permitido y el resultado es que TODA respuesta de error
                                // —409 de email repetido, 401 de credenciales, 400 de validación,
                                // 405— sale al cliente convertida en un 403 con cuerpo vacío.
                                // El filtro de autorización cubre todos los tipos de despacho
                                // desde Spring Security 6, así que hay que excluirlos a mano.
                                // Ojo: esto no expone /error, que como petición directa sigue
                                // siendo un despacho REQUEST y sigue protegido.
                                auth.dispatcherTypeMatchers(
                                                DispatcherType.ERROR, DispatcherType.ASYNC)
                                        .permitAll()
                                        .requestMatchers("/api/v1/auth/**", "/api/v1/health")
                                        .permitAll()
                                        // El handshake de WebSocket es publico a nivel HTTP: la
                                        // autenticacion ocurre en el frame CONNECT de STOMP, que
                                        // el StompChannelInterceptor rechaza si no trae token.
                                        // Un socket abierto sin CONNECT valido no puede ni
                                        // suscribirse ni mutar nada.
                                        .requestMatchers("/ws/**")
                                        .permitAll()
                                        // Mirar un enlace de invitacion es publico: quien lo
                                        // recibe tiene que poder ver que es antes de decidir si
                                        // se registra. No expone nada — sin un token valido no
                                        // devuelve ni el nombre del proyecto. Aceptarlo, en
                                        // cambio, exige estar autenticado: hay que saber a quien
                                        // se agrega, y por eso el POST .../accept queda afuera
                                        // (este patron cubre un solo segmento).
                                        .requestMatchers(
                                                HttpMethod.GET, "/api/v1/invitations/*")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                // Sin esto Spring usa Http403ForbiddenEntryPoint y una petición SIN token
                // responde 403, que significa "sé quién sos y no te alcanza". Lo correcto es
                // 401: no hay credenciales. La diferencia le importa al cliente, que ante un
                // 401 tiene que mandar al usuario a login y ante un 403 no.
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(
                        jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * CORS permisivo, sólo válido para desarrollo. En AWS (H3) Nginx queda delante y el origen
     * se restringe al dominio real; queda anotado para no olvidarlo en el despliegue.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
