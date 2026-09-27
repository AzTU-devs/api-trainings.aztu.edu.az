package com.eduplatform.eduplatform_backend.identity.service;

import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.UUID;

/**
 * Whether an access token still speaks for its account.
 *
 * <p>Access tokens are self-contained JWTs, so on their own they stay valid for their whole
 * lifetime whatever happens to the account: a disabled or deleted user, or an ADMIN demoted to
 * USER, kept every permission in the token for up to 15 minutes. The token now carries the
 * account's {@code token_version} as its {@code ver} claim, and {@link #accepts} compares it,
 * together with the account's status, against the database on every authenticated request.
 *
 * <p>The comparison is cached per user for {@link #CACHE_TTL}, so a signed-in user costs one
 * primary-key select per half minute rather than one per request. Every change that must take
 * effect at once goes through {@link #revokeAccessTokens} or {@link #invalidateAfterCommit}, which
 * drop the entry as soon as the change commits; the TTL only bounds a change made behind the
 * application's back, such as by hand in SQL.
 *
 * <p>Single instance assumed, as for the refresh-token grace cache: a second API instance would
 * see the change within {@link #CACHE_TTL} rather than at once.
 */
@Service
public class UserSessionState {

    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    /** Cached for a user who does not exist (deleted, or never did), so a stale token is refused cheaply. */
    private static final Snapshot MISSING = new Snapshot(null, -1);

    private final UserRepository users;

    private final Cache<UUID, Snapshot> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(100_000)
            .build();

    public UserSessionState(UserRepository users) {
        this.users = users;
    }

    /**
     * True while the account exists, is not disabled and has not been changed since the token
     * was issued. LOCKED is accepted: a lockout is about guessing passwords, and ending the real
     * owner's session because somebody else guessed would hand that somebody a way to sign
     * anyone out.
     */
    public boolean accepts(UUID userId, long tokenVersion) {
        Snapshot s = cache.get(userId, id -> users.findSessionState(id)
                .map(v -> new Snapshot(v.getStatus(), v.getTokenVersion()))
                .orElse(MISSING));
        return s != MISSING && isSignInStatus(s.status()) && s.tokenVersion() == tokenVersion;
    }

    /** Whether an account in this status may hold a session at all. */
    public static boolean isSignInStatus(UserStatus status) {
        return status == UserStatus.ACTIVE || status == UserStatus.LOCKED;
    }

    /**
     * Makes every access token already issued to {@code user} stale. Written through the managed
     * entity rather than an UPDATE statement, because the caller is usually about to flush that
     * same entity, and Hibernate writes every column: a separate increment would be overwritten
     * by the old value.
     */
    public void revokeAccessTokens(User user) {
        user.setTokenVersion(user.getTokenVersion() + 1);
        invalidateAfterCommit(user.getId());
    }

    /**
     * Forgets the cached state once the current transaction has finished. Not before: a request
     * arriving between the eviction and the commit would read, and cache for the whole TTL, the
     * state that is about to change.
     */
    public void invalidateAfterCommit(UUID userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.invalidate(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                cache.invalidate(userId);
            }
        });
    }

    private record Snapshot(UserStatus status, long tokenVersion) {}
}
