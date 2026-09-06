package com.modelcollab.auth.service;

import com.modelcollab.auth.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Emisión y verificación de los JWT de la plataforma. HS256 con clave simétrica.
 *
 * <p>HS256 (y no RS256) porque el emisor y el verificador son el mismo proceso: no hay un
 * tercero que necesite validar tokens sin poder firmarlos, así que el par de claves
 * asimétrico sólo agregaría gestión de claves sin comprar nada.
 *
 * <p>El {@code sub} es el {@code userId} (UUID), no el email: el email es un dato mutable
 * y si un usuario lo cambia, todos sus tokens vivos pasarían a apuntar a un sujeto que ya
 * no existe. El email viaja como claim informativo para los logs y la UI.
 */
@Service
public class JwtService {

    /** HS256 exige 256 bits de clave. Ver RFC 7518 §3.2. */
    private static final int LARGO_MINIMO_BYTES = 32;

    private final SecretKey clave;
    private final long expiracionMs;

    public JwtService(
            @Value("${jwt.secret}") String secreto,
            @Value("${jwt.expiration-ms}") long expiracionMs) {
        byte[] bytes = secreto.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < LARGO_MINIMO_BYTES) {
            // Falla al arrancar y no al firmar el primer token: un secreto corto puesto por
            // error en AWS tiene que romper el despliegue, no el primer login de un usuario.
            throw new IllegalStateException(
                    "jwt.secret debe tener al menos " + LARGO_MINIMO_BYTES
                            + " bytes para HS256; tiene " + bytes.length);
        }
        this.clave = Keys.hmacShaKeyFor(bytes);
        this.expiracionMs = expiracionMs;
    }

    /** Token firmado para el usuario dado. */
    public String generate(User user) {
        Instant ahora = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plusMillis(expiracionMs)))
                .signWith(clave)
                .compact();
    }

    /**
     * Verifica firma y expiración y devuelve el userId del {@code sub}.
     *
     * @throws JwtException si el token está vencido, alterado, mal formado o firmado con
     *     otra clave. No devuelve {@code null} ni {@code Optional}: un token inválido es una
     *     condición excepcional en ambos usos (filtro HTTP y CONNECT de STOMP), y devolver un
     *     valor vacío invita a que alguien se olvide de chequearlo.
     */
    public UUID parse(String token) {
        Claims claims =
                Jwts.parser().verifyWith(clave).build().parseSignedClaims(token).getPayload();
        try {
            return UUID.fromString(claims.getSubject());
        } catch (IllegalArgumentException e) {
            throw new JwtException("El subject del token no es un UUID: " + claims.getSubject(), e);
        }
    }
}
