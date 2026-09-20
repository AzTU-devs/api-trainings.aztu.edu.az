package com.eduplatform.eduplatform_backend.identity.repo;

import com.eduplatform.eduplatform_backend.common.enums.TokenRevokeReason;
import com.eduplatform.eduplatform_backend.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes one token only if nothing has revoked it yet, and reports whether this call did.
     * Rotation relies on this being a single conditional UPDATE: of any number of requests
     * racing on the same token, Postgres lets exactly one match {@code revoked_at is null}; the
     * others wait on its row lock, re-check the predicate against its committed row and get 0.
     */
    @Modifying
    @Query("""
           update RefreshToken t
             set t.revokedAt = :ts, t.revokeReason = :reason
           where t.id = :id and t.revokedAt is null
           """)
    int revokeIfActive(@Param("id") UUID id,
                       @Param("reason") TokenRevokeReason reason,
                       @Param("ts") Instant ts);

    /**
     * The token's revocation as committed in the database. A scalar projection, so it is read
     * from the row even when the entity is already in the persistence context with older state
     * (which is the case right after losing a {@link #revokeIfActive} race).
     */
    @Query("""
           select t.revokedAt as revokedAt, t.revokeReason as revokeReason
           from RefreshToken t
           where t.id = :id
           """)
    Optional<Revocation> findRevocationById(@Param("id") UUID id);

    /**
     * Whether this exact token is still usable. The grace path checks the child a rotation issued
     * before handing it out a second time, so a family that has since been ended — logout, password
     * reset, reuse detection — cannot be reopened by a straggling replay.
     */
    boolean existsByIdAndRevokedAtIsNullAndExpiresAtAfter(UUID id, Instant now);

    /**
     * Revokes every live token in the family.
     *
     * <p>Plain propagation on purpose. This used to be {@code REQUIRES_NEW}, because reuse
     * detection revokes the family and then fails the request with a 401, and that exception would
     * otherwise roll the revocation back. But a nested transaction needs a second pooled connection
     * while the caller still holds its own, so eleven concurrent replays could exhaust a
     * twenty-connection pool and 500 every other request on the server. The caller now keeps the
     * revocation instead, by declaring the reuse exception in {@code noRollbackFor} — one
     * connection per request, same outcome.
     */
    @Modifying
    @Query("""
           update RefreshToken t
             set t.revokedAt = :ts, t.revokeReason = :reason
           where t.familyId = :familyId and t.revokedAt is null
           """)
    int revokeFamily(@Param("familyId") UUID familyId,
                     @Param("reason") TokenRevokeReason reason,
                     @Param("ts") Instant ts);

    @Modifying
    @Query("""
           update RefreshToken t
             set t.revokedAt = :ts, t.revokeReason = :reason
           where t.user.id = :userId and t.revokedAt is null
           """)
    int revokeAllForUser(@Param("userId") UUID userId,
                         @Param("reason") TokenRevokeReason reason,
                         @Param("ts") Instant ts);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);

    /** Revocation columns of one token; see {@link #findRevocationById}. */
    interface Revocation {
        Instant getRevokedAt();
        TokenRevokeReason getRevokeReason();
    }
}
