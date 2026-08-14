package com.eduplatform.eduplatform_backend.identity.service;

import com.eduplatform.eduplatform_backend.common.enums.TokenRevokeReason;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.TokenHasher;
import com.eduplatform.eduplatform_backend.common.security.config.JwtProperties;
import com.eduplatform.eduplatform_backend.identity.domain.RefreshToken;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.RefreshTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Issues, rotates and revokes refresh tokens with **reuse-detection**:
 * if a previously-rotated token is presented again, the entire family is revoked.
 */
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository repo;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repo, JwtProperties jwt) {
        this.repo = repo;
        this.ttl = Duration.ofDays(jwt.refreshTtlDays());
    }

    /** Issue a brand-new refresh token (new family). */
    @Transactional
    public Rotated issueNew(User user, HttpServletRequest req) {
        UUID familyId = UUID.randomUUID();
        return persist(user, familyId, null, req);
    }

    /**
     * Rotate an existing token: validate it, mark it ROTATED, return a fresh token in the same family.
     * If the presented token was already revoked → reuse detected: revoke the whole family.
     */
    @Transactional
    public Rotated rotate(String rawToken, HttpServletRequest req) {
        String hash = TokenHasher.sha256Hex(rawToken);
        RefreshToken stored = repo.findByTokenHash(hash)
                .orElseThrow(() -> Errors.unauthorized("INVALID_REFRESH_TOKEN", "Refresh token is invalid"));

        if (stored.getRevokedAt() != null) {
            repo.revokeFamily(stored.getFamilyId(), TokenRevokeReason.REUSE_DETECTED, Instant.now());
            throw Errors.unauthorized("REFRESH_TOKEN_REUSED",
                    "Refresh token reuse detected; all sessions in this family have been revoked");
        }
        if (Instant.now().isAfter(stored.getExpiresAt())) {
            throw Errors.unauthorized("REFRESH_TOKEN_EXPIRED", "Refresh token has expired");
        }

        stored.setRevokedAt(Instant.now());
        stored.setRevokeReason(TokenRevokeReason.ROTATED);
        repo.save(stored);

        return persist(stored.getUser(), stored.getFamilyId(), stored.getId(), req);
    }

    @Transactional
    public void revoke(String rawToken, TokenRevokeReason reason) {
        repo.findByTokenHash(TokenHasher.sha256Hex(rawToken)).ifPresent(t -> {
            if (t.getRevokedAt() == null) {
                t.setRevokedAt(Instant.now());
                t.setRevokeReason(reason);
                repo.save(t);
            }
        });
    }

    @Transactional
    public void revokeAllForUser(UUID userId, TokenRevokeReason reason) {
        repo.revokeAllForUser(userId, reason, Instant.now());
    }

    private Rotated persist(User user, UUID familyId, UUID parentId, HttpServletRequest req) {
        String raw = TokenHasher.randomToken(32);
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);

        RefreshToken token = RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tokenHash(TokenHasher.sha256Hex(raw))
                .familyId(familyId)
                .parentId(parentId)
                .issuedAt(now)
                .expiresAt(exp)
                .ipAddress(req == null ? null : req.getRemoteAddr())
                .userAgent(req == null ? null : truncate(req.getHeader("User-Agent"), 255))
                .build();
        repo.save(token);
        return new Rotated(raw, exp, user);
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }

    /** Both the raw token (only seen once by the caller) and the owning user. */
    public record Rotated(String rawToken, Instant expiresAt, User user) {}
}
