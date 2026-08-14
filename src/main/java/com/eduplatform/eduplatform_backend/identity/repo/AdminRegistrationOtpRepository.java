package com.eduplatform.eduplatform_backend.identity.repo;

import com.eduplatform.eduplatform_backend.identity.domain.AdminRegistrationOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AdminRegistrationOtpRepository extends JpaRepository<AdminRegistrationOtp, UUID> {

    @Query("""
           select o from AdminRegistrationOtp o
           where lower(o.email) = lower(:email) and o.consumedAt is null
           order by o.createdAt desc
           """)
    Optional<AdminRegistrationOtp> findActiveByEmail(@Param("email") String email);

    @Modifying
    @Query("delete from AdminRegistrationOtp o where o.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
