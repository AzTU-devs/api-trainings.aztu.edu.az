package com.eduplatform.eduplatform_backend.identity.repo;

import com.eduplatform.eduplatform_backend.identity.domain.OAuthAuthState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface OAuthAuthStateRepository extends JpaRepository<OAuthAuthState, String> {

    @Modifying
    @Query("delete from OAuthAuthState s where s.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
