package com.eduplatform.eduplatform_backend.identity.repo;

import com.eduplatform.eduplatform_backend.common.enums.AuthActionPurpose;
import com.eduplatform.eduplatform_backend.identity.domain.AuthActionToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthActionTokenRepository extends JpaRepository<AuthActionToken, UUID> {

    Optional<AuthActionToken> findByTokenHashAndPurposeAndConsumedAtIsNull(String tokenHash, AuthActionPurpose purpose);

    @Modifying
    @Query("update AuthActionToken t set t.consumedAt = :ts where t.userId = :userId and t.purpose = :purpose and t.consumedAt is null")
    int consumeAllForUser(@Param("userId") UUID userId, @Param("purpose") AuthActionPurpose purpose, @Param("ts") Instant ts);

    @Modifying
    @Query("delete from AuthActionToken t where t.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
