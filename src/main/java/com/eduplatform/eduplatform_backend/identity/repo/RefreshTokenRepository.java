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
}
