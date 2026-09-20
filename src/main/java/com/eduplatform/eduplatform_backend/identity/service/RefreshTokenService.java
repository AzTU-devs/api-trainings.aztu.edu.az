package com.eduplatform.eduplatform_backend.identity.service;

import com.eduplatform.eduplatform_backend.common.enums.TokenRevokeReason;
import com.eduplatform.eduplatform_backend.common.error.AppException;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.TokenHasher;
import com.eduplatform.eduplatform_backend.common.security.config.JwtProperties;
import com.eduplatform.eduplatform_backend.identity.domain.RefreshToken;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.RefreshTokenRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Issues, rotates and revokes refresh tokens with **reuse-detection**:
 * if a previously-rotated token is presented again, the entire family is revoked.
 *
 * <p>One exception: for {@link #CONCURRENT_ROTATION_GRACE} after a token is rotated, replaying it
 * returns <em>the very same</em> child the rotation issued, from {@link #rotationReplies}. Two tabs,
 * or the web BFF's proxy and its client-side interceptor, routinely refresh with the same token at
 * the same moment — the public site's link prefetching makes that the normal case once the access
 * cookie has expired — and treating the losers as thieves would sign the user out everywhere.
 *
 * <p>Replaying is idempotent rather than "allowed once more" for two reasons. Minting a sibling per
 * replay would let anyone holding a just-rotated token mint unlimited 30-day sessions; capping it at
 * one instead needed a row lock, and requests queued on that lock each held a pooled connection, so
 * about twenty concurrent replays exhausted the pool and took the whole API down with them. Handing
 * back the existing child needs no lock and no write, so any amount of concurrency is harmless, and
 * no session exists that the real user does not already hold. Theft is still caught: both parties
 * hold the same token, and whoever rotates it next leaves the other to be detected on its next use
 * past the window.
 */
@Service
public class RefreshTokenService {

    /**
     * Long enough to cover a slow second request in a multi-tab or session-restore burst, short
     * enough that a token stolen from an old response is almost always presented after it.
     */
    static final Duration CONCURRENT_ROTATION_GRACE = Duration.ofSeconds(30);

    /**
     * Rotated token id → the child that rotation handed out, kept for the grace window.
     *
     * <p>In memory, because it is a cache and not a fact: losing it costs a straggling replay its
     * grace, nothing more. Bounded and time-expiring so it cannot grow without limit — 100k entries
     * is far more concurrent rotations than this deployment will ever have in any 30-second window.
     *
     * <p>It holds a raw refresh token, which is deliberate: the database stores only a hash, so the
     * child cannot be handed out again from there. It lives no longer than the window, in the same
     * process that just minted it and put it in a response body.
     *
     * <p>Single instance assumed. Behind two API instances a replay landing on the other one finds
     * no entry and is treated as reuse, ending the session; moving this to a shared store (or
     * sticky sessions) is what a second instance would need.
     */
    private final Cache<UUID, GraceReply> rotationReplies = Caffeine.newBuilder()
            .expireAfterWrite(CONCURRENT_ROTATION_GRACE)
            .maximumSize(100_000)
            .build();

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
     * Rotate an existing token: mark it ROTATED and return a fresh token in the same family.
     * Both the web BFF (token in the request body) and the admin portal (token in the httpOnly
     * cookie) arrive here, so both get the same race handling.
     *
     * <p>The ROTATED mark is a conditional UPDATE, not a read-modify-save of the entity: two
     * requests could otherwise both read "not revoked", both save, and both walk away with a
     * child token without either knowing about the other.
     */
    @Transactional(noRollbackFor = ReuseDetected.class)
    public Rotated rotate(String rawToken, HttpServletRequest req) {
        RefreshToken stored = repo.findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .orElseThrow(RefreshTokenService::invalid);
        Instant now = Instant.now();

        Instant revokedAt = stored.getRevokedAt();
        TokenRevokeReason revokeReason = stored.getRevokeReason();
        if (revokedAt == null) {
            if (now.isAfter(stored.getExpiresAt())) {
                throw expired();
            }
            if (repo.revokeIfActive(stored.getId(), TokenRevokeReason.ROTATED, now) == 1) {
                return persist(stored.getUser(), stored.getFamilyId(), stored.getId(), req);
            }
            // Another request revoked it between our read and our update. `stored` still holds
            // what we read, so take the revocation from the row as that request committed it.
            RefreshTokenRepository.Revocation committed = repo.findRevocationById(stored.getId())
                    .orElseThrow(RefreshTokenService::invalid);
            revokedAt = committed.getRevokedAt();
            revokeReason = committed.getRevokeReason();
        }

        if (isRecentRotation(revokeReason, revokedAt, now)) {
            if (now.isAfter(stored.getExpiresAt())) {
                throw expired();
            }
            GraceReply reply = rotationReplies.getIfPresent(stored.getId());
            // The child has to still be usable. Checking it rather than the family answers the
            // question that matters — can the caller actually use what we are about to return —
            // and it refuses to reopen a session that logout, a password reset or an earlier reuse
            // has already ended.
            if (reply != null && repo.existsByIdAndRevokedAtIsNullAndExpiresAtAfter(reply.childId(), now)) {
                return new Rotated(reply.rawToken(), reply.expiresAt(), stored.getUser());
            }
        }

        // Revoke in this transaction and let it commit: ReuseDetected is in noRollbackFor here and
        // on AuthService.refresh, so the 401 does not undo the revocation. See revokeFamily for why
        // this is not a REQUIRES_NEW transaction any more.
        repo.revokeFamily(stored.getFamilyId(), TokenRevokeReason.REUSE_DETECTED, now);
        throw new ReuseDetected();
    }

    /**
     * Revokes the token if it is still live. A token that is already revoked keeps its original
     * reason, so a logout racing a rotation cannot relabel the ROTATED mark.
     */
    @Transactional
    public void revoke(String rawToken, TokenRevokeReason reason) {
        repo.findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .ifPresent(t -> repo.revokeIfActive(t.getId(), reason, Instant.now()));
    }

    @Transactional
    public void revokeAllForUser(UUID userId, TokenRevokeReason reason) {
        repo.revokeAllForUser(userId, reason, Instant.now());
    }

    private static boolean isRecentRotation(TokenRevokeReason reason, Instant revokedAt, Instant now) {
        return reason == TokenRevokeReason.ROTATED
                && revokedAt != null
                && !revokedAt.isBefore(now.minus(CONCURRENT_ROTATION_GRACE));
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
        Rotated rotated = new Rotated(raw, exp, user);
        if (parentId != null) {
            rememberForGrace(parentId, new GraceReply(raw, exp, token.getId()));
        }
        return rotated;
    }

    /**
     * Publishes the reply only once the rotation is committed. Caching it earlier would let a replay
     * be handed a token whose INSERT then rolled back — a token the caller could never use.
     */
    private void rememberForGrace(UUID parentId, GraceReply reply) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            rotationReplies.put(parentId, reply);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rotationReplies.put(parentId, reply);
            }
        });
    }

    private static AppException invalid() {
        return Errors.unauthorized("INVALID_REFRESH_TOKEN", "Refresh token is invalid");
    }

    private static AppException expired() {
        return Errors.unauthorized("REFRESH_TOKEN_EXPIRED", "Refresh token has expired");
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }

    /** Both the raw token (only seen once by the caller) and the owning user. */
    public record Rotated(String rawToken, Instant expiresAt, User user) {}

    /** What a rotation handed out, so the same answer can be repeated inside the grace window. */
    private record GraceReply(String rawToken, Instant expiresAt, UUID childId) {}

    /**
     * Reuse detected: the family has just been revoked and the caller must be refused.
     *
     * <p>Its own type so that {@code noRollbackFor} can name it. The revocation is written in the
     * same transaction as the request, so without that the 401 would roll it back and leave every
     * session in the family alive — and doing it in a nested transaction instead is what made
     * concurrent replays exhaust the connection pool.
     */
    public static class ReuseDetected extends AppException {
        ReuseDetected() {
            super(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REUSED",
                    "Refresh token reuse detected; all sessions in this family have been revoked");
        }
    }
}
