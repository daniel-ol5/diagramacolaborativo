package com.modelcollab.config;

import com.modelcollab.auth.service.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Traduce {@code Authorization: Bearer <jwt>} a una autenticación de Spring Security para las
 * peticiones REST. El equivalente para WebSocket vive en {@code StompChannelInterceptor}: son
 * dos caminos distintos porque STOMP no pasa por la cadena de filtros del servlet.
 *
 * <p>Un token inválido <b>no</b> corta la petición acá: simplemente no se establece
 * autenticación y la decisión queda en manos de las reglas de {@code SecurityConfig}. Así una
 * ruta pública sigue funcionando aunque el cliente mande un token vencido, y una privada
 * responde 401 por el camino normal de Spring Security en vez de por una excepción del filtro.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    static final String HEADER = "Authorization";
    static final String PREFIJO = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HEADER);
        if (header != null
                && header.startsWith(PREFIJO)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                UUID userId = jwtService.parse(header.substring(PREFIJO.length()));
                var auth =
                        new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException e) {
                logger.debug("Token Bearer rechazado: " + e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
