package com.modelcollab.auth.service;

import com.modelcollab.auth.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
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

    /**
     * El secreto de desarrollo que vivio como valor por defecto en {@code application.yml} y que
     * por lo tanto <b>esta publicado en el repositorio</b>.
     *
     * <p>Sigue en el codigo a proposito, y no es una contradiccion: ya es publico, y tenerlo aca
     * es lo unico que permite rechazarlo. El camino que cierra es concreto — alguien que clona
     * el repositorio, ve el fallo al arrancar, busca en el historial cual era el valor y lo
     * exporta tal cual. Sin esta lista, ese despliegue arrancaria con una clave que cualquiera
     * puede leer en GitHub.
     */
    private static final Set<String> SECRETOS_QUEMADOS = Set.of(
            "modelcollab-desarrollo-local-clave-hs256-no-usar-en-produccion-64b");

    private static final String COMO_GENERAR =
            "Genera uno con: openssl rand -base64 48   (o en PowerShell: "
                    + "[Convert]::ToBase64String((1..48|%{Get-Random -Max 256})))";

    private final SecretKey clave;
    private final long expiracionMs;

    public JwtService(
            @Value("${jwt.secret:}") String secreto,
            @Value("${jwt.expiration-ms}") long expiracionMs) {

        // Sin secreto la aplicacion NO ARRANCA. Antes habia un valor por defecto y el
        // comentario que lo acompaniaba decia lo correcto —"no usar en produccion"— pero eso
        // no lo impedia: un despliegue que se olvidara de inyectar JWT_SECRET levantaba
        // perfecto, sin error y sin aviso, firmando con una clave publicada en GitHub. Con esa
        // clave cualquiera que haya leido el repositorio se fabrica un token valido para
        // cualquier usuario.
        //
        // Esa es la diferencia con la contrasenia de la base, que si conserva su valor por
        // defecto: un despliegue mal configurado contra PostgreSQL FALLA CERRADO —no conecta y
        // se nota en el acto—, mientras que este fallaba ABIERTO: funcionaba igual de bien y
        // completamente expuesto. Un fallo silencioso en la direccion peligrosa es el unico que
        // hay que volver ruidoso.
        if (secreto == null || secreto.isBlank()) {
            throw new IllegalStateException(
                    "Falta JWT_SECRET. La aplicacion no arranca sin el a proposito: con un valor "
                            + "por defecto, un despliegue que se olvide de configurarlo firma los "
                            + "tokens con una clave publica. " + COMO_GENERAR);
        }
        if (SECRETOS_QUEMADOS.contains(secreto.strip())) {
            throw new IllegalStateException(
                    "JWT_SECRET es el secreto de desarrollo que esta publicado en el "
                            + "repositorio. Cualquiera que lo lea puede firmar tokens a nombre de "
                            + "cualquier usuario. " + COMO_GENERAR);
        }

        byte[] bytes = secreto.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < LARGO_MINIMO_BYTES) {
            // Falla al arrancar y no al firmar el primer token: un secreto corto puesto por
            // error en AWS tiene que romper el despliegue, no el primer login de un usuario.
            throw new IllegalStateException(
                    "jwt.secret debe tener al menos " + LARGO_MINIMO_BYTES
                            + " bytes para HS256; tiene " + bytes.length + ". " + COMO_GENERAR);
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
