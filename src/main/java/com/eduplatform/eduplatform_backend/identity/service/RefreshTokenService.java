package com.eduplatform.eduplatform_backend.identity.service;

import com.eduplatform.eduplatform_backend.audit.service.HttpMeta;
import com.eduplatform.eduplatform_backend.common.enums.TokenRevokeReason;
import com.eduplatform.eduplatform_backend.common.error.AppException;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.TokenHasher;
import com.eduplatform.eduplatform_backend.common.security.config.JwtProperties;
import com.eduplatform.eduplatform_backend.identity.domain.RefreshToken;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.RefreshTokenRepository;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /** How long a replay waits for a concurrent rotation to finish committing; see awaitGraceReply. */
    private static final Duration GRACE_REPLY_WAIT = Duration.ofSeconds(2);

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
     *
     * <p>The value is a future, put in place while the rotation is still in its transaction and
     * completed once it commits (see {@link #rememberForGrace}). Publishing only after the commit
     * left a gap: a replay could read the committed ROTATED row, find no entry yet, and revoke the
     * whole family as reused — signing the user out everywhere in exactly the multi-tab burst the
     * window exists for.
     */
    private final Cache<UUID, CompletableFuture<GraceReply>> rotationReplies = Caffeine.newBuilder()
            .expireAfterWrite(CONCURRENT_ROTATION_GRACE)
            .maximumSize(100_000)
            .build();

    private final RefreshTokenRepository repo;
    private final UserRepository users;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repo, UserRepository users, JwtProperties jwt) {
        this.repo = repo;
        this.users = users;
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
        User owner = requireSignInAllowed(stored);
        Instant now = Instant.now();

        Instant revokedAt = stored.getRevokedAt();
        TokenRevokeReason revokeReason = stored.getRevokeReason();
        if (revokedAt == null) {
            if (now.isAfter(stored.getExpiresAt())) {
                throw expired();
            }
            if (repo.revokeIfActive(stored.getId(), TokenRevokeReason.ROTATED, now) == 1) {
                return persist(owner, stored.getFamilyId(), stored.getId(), req);
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
            GraceReply reply = awaitGraceReply(rotationReplies.getIfPresent(stored.getId()));
            // The child has to still be usable. Checking it rather than the family answers the
            // question that matters — can the caller actually use what we are about to return —
            // and it refuses to reopen a session that logout, a password reset or an earlier reuse
            // has already ended.
            if (reply != null && repo.existsByIdAndRevokedAtIsNullAndExpiresAtAfter(reply.childId(), now)) {
                return new Rotated(reply.rawToken(), reply.expiresAt(), owner);
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
     * reason, so a logout racing a rotation cannot relabel the ROTATED mark. Returns the owner's
     * id when this call is what revoked it.
     */
    @Transactional
    public Optional<UUID> revoke(String rawToken, TokenRevokeReason reason) {
        return repo.findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .filter(t -> repo.revokeIfActive(t.getId(), reason, Instant.now()) == 1)
                // The id off the lazy proxy; the account itself is not loaded.
                .map(t -> t.getUser().getId());
    }

    @Transactional
    public void revokeAllForUser(UUID userId, TokenRevokeReason reason) {
        repo.revokeAllForUser(userId, reason, Instant.now());
    }

    /**
     * Revokes every session of the user except the one {@code keepRawToken} belongs to — if it is
     * the user's own — so a password change signs out every other device and not the one making it.
     */
    @Transactional
    public void revokeOtherSessions(UUID userId, String keepRawToken, TokenRevokeReason reason) {
        UUID keepFamily = keepRawToken == null ? null : repo.findByTokenHash(TokenHasher.sha256Hex(keepRawToken))
                .filter(t -> t.getUser().getId().equals(userId))
                .map(RefreshToken::getFamilyId)
                .orElse(null);
        if (keepFamily == null) {
            repo.revokeAllForUser(userId, reason, Instant.now());
        } else {
            repo.revokeAllForUserExceptFamily(userId, keepFamily, reason, Instant.now());
        }
    }

    /**
     * The token's owner, provided the account may still hold a session. Sign-in checks the
     * account's status, and a refresh is a sign-in that skips the password, so it has to check the
     * same thing — or disabling an account ends nothing: every rotation hands out another 30-day
     * token, and the session outlives the decision indefinitely.
     *
     * <p>Loaded with a query rather than through {@code stored.getUser()}: the association is a lazy
     * proxy, and for a soft-deleted account {@code @SQLRestriction} makes initialising it throw
     * EntityNotFoundException, which surfaced as a 500 and rolled the rotation back. Reading the id
     * off the proxy does not initialise it.
     *
     * <p>LOCKED is let through. A lockout stops password guessing; refusing the real owner's
     * refresh because a stranger typed their address wrong five times would hand that stranger a
     * way to sign anyone out. The inside of the grace window is checked too, because it is reached
     * through here.
     */
    private User requireSignInAllowed(RefreshToken stored) {
        User owner = users.findById(stored.getUser().getId())
                .orElseThrow(RefreshTokenService::invalid);
        if (!UserSessionState.isSignInStatus(owner.getStatus())) {
            throw Errors.unauthorized("ACCOUNT_NOT_ACTIVE", "This account is disabled");
        }
        return owner;
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
                .ipAddress(HttpMeta.clientIp(req))
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
     * Registers the reply as a future before the rotation commits and completes it once it has.
     * Registered first, so a replay that sees the committed ROTATED row always finds it; completed
     * only on commit, so a replay is never handed a token whose INSERT then rolled back — a token
     * the caller could never use. A rolled-back rotation withdraws the entry and fails the future,
     * and the replay is then judged like any other.
     */
    private void rememberForGrace(UUID parentId, GraceReply reply) {
        CompletableFuture<GraceReply> pending = new CompletableFuture<>();
        rotationReplies.put(parentId, pending);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            pending.complete(reply);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    pending.complete(reply);
                } else {
                    rotationReplies.asMap().remove(parentId, pending);
                    pending.completeExceptionally(new IllegalStateException("rotation rolled back"));
                }
            }
        });
    }

    /**
     * The rotation's reply once it has committed. Waited for briefly: a replay only finds a future
     * that is still pending in the moment between the rotation's COMMIT and its completion, so the
     * wait is normally microseconds; the timeout only bounds a rotation that is stuck.
     */
    private static GraceReply awaitGraceReply(CompletableFuture<GraceReply> pending) {
        if (pending == null) return null;
        try {
            return pending.get(GRACE_REPLY_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException rolledBackOrStuck) {
            return null;
        }
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
