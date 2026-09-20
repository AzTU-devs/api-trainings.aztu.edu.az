package com.eduplatform.eduplatform_backend.identity.repo;

import com.eduplatform.eduplatform_backend.identity.domain.AdminRegistrationOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Counts one wrong OTP, committed in a transaction of its own before this returns. The wrong
     * guess fails the request with a 401, which rolls back the caller's transaction; an increment
     * made inside it was undone with it, so the attempt cap was never reached.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query("update AdminRegistrationOtp o set o.attempts = o.attempts + 1 where o.id = :id")
    int recordFailedAttemptAndCommit(@Param("id") UUID id);

    @Modifying
    @Query("delete from AdminRegistrationOtp o where o.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
