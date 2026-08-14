package com.eduplatform.eduplatform_backend.common.security;

import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and validates short-lived access tokens (HS256).
 * Refresh tokens are opaque and managed separately by {@code RefreshTokenService}.
 */
@Service
public class JwtService {

    private final JwtProperties props;
    private final SecretKey signingKey;
    private final Duration accessTtl;

    public JwtService(JwtProperties props) {
        if (props.accessSecret() == null || props.accessSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "app.security.jwt.access-secret must be at least 32 bytes (256 bits) for HS256");
        }
        this.props = props;
        this.signingKey = Keys.hmacShaKeyFor(props.accessSecret().getBytes(StandardCharsets.UTF_8));
        this.accessTtl = Duration.ofMinutes(props.accessTtlMinutes());
    }

    public IssuedToken issueAccess(UUID userId, String email, List<String> roles, List<String> permissions) {
        Instant now = Instant.now();
        Instant exp = now.plus(accessTtl);
        String jwt = Jwts.builder()
                .issuer(props.issuer())
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .id(UUID.randomUUID().toString())
                .claim("email", email)
                .claim("roles", roles)
                .claim("perms", permissions)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(jwt, exp);
    }

    /** Parse and validate; throws 401 {@link com.eduplatform.eduplatform_backend.common.error.AppException} on failure. */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(props.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException ex) {
            throw Errors.unauthorized("INVALID_TOKEN", "Access token is invalid or expired");
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> stringList(Map<String, Object> claims, String key) {
        Object v = claims.get(key);
        return v instanceof List<?> l ? (List<String>) l : List.of();
    }

    public record IssuedToken(String token, Instant expiresAt) {}
}
