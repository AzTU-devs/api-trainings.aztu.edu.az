package com.eduplatform.eduplatform_backend.identity.oauth;

import com.eduplatform.eduplatform_backend.common.security.config.OAuthProperties;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Builds Apple's OAuth "client_secret" — itself a short-lived ES256-signed JWT.
 * Cached in-memory and rotated every 50 minutes (Apple's hard ceiling is 6 months but Apple staff
 * recommend short-lived refresh; ours expires after 1 hour and is silently regenerated).
 *
 * Inputs (all from {@code app.oauth.apple.*}):
 *   - team-id   : Apple Developer Team ID  (iss)
 *   - services-id : your "Service ID"      (sub + the OIDC aud)
 *   - key-id    : the .p8 key's "kid" header
 *   - private-key : PEM contents of the .p8 file
 */
@Service
public class AppleClientSecretService {

    private static final Duration TTL = Duration.ofMinutes(50);
    private static final String AUDIENCE = "https://appleid.apple.com";

    private final OAuthProperties.Apple cfg;
    private volatile Cached cached;

    public AppleClientSecretService(OAuthProperties props) {
        this.cfg = props.apple();
    }

    public String currentSecret() {
        Cached snap = cached;
        if (snap != null && Instant.now().isBefore(snap.expiresAt.minus(Duration.ofMinutes(2)))) {
            return snap.token;
        }
        synchronized (this) {
            snap = cached;
            if (snap != null && Instant.now().isBefore(snap.expiresAt.minus(Duration.ofMinutes(2)))) {
                return snap.token;
            }
            Cached fresh = build();
            cached = fresh;
            return fresh.token;
        }
    }

    private Cached build() {
        if (cfg.privateKey() == null || cfg.privateKey().isBlank()) {
            throw new IllegalStateException("app.oauth.apple.private-key is not configured");
        }
        try {
            PrivateKey key = parsePkcs8(cfg.privateKey());
            Instant now = Instant.now();
            Instant exp = now.plus(TTL);

            String jwt = Jwts.builder()
                    .header().keyId(cfg.keyId()).and()
                    .issuer(cfg.teamId())
                    .subject(cfg.servicesId())
                    .audience().add(AUDIENCE).and()
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(exp))
                    .signWith(key, Jwts.SIG.ES256)
                    .compact();
            return new Cached(jwt, exp);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build Apple client_secret JWT", ex);
        }
    }

    private static PrivateKey parsePkcs8(String pem) throws Exception {
        String stripped = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(stripped);
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private record Cached(String token, Instant expiresAt) {}
}
