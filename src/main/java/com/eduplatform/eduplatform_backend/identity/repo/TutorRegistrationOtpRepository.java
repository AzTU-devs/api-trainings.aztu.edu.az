package com.eduplatform.eduplatform_backend.identity.repo;

import com.eduplatform.eduplatform_backend.identity.domain.TutorRegistrationOtp;
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
public interface TutorRegistrationOtpRepository extends JpaRepository<TutorRegistrationOtp, UUID> {

    @Query("""
           select o from TutorRegistrationOtp o
           where lower(o.email) = lower(:email) and o.consumedAt is null
           order by o.createdAt desc
           """)
    Optional<TutorRegistrationOtp> findActiveByEmail(@Param("email") String email);

    /**
     * Counts one wrong OTP, committed in a transaction of its own before this returns. The wrong
     * guess fails the request with a 401, which rolls back the caller's transaction; an increment
     * made inside it was undone with it, so the attempt cap was never reached.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query("update TutorRegistrationOtp o set o.attempts = o.attempts + 1 where o.id = :id")
    int recordFailedAttemptAndCommit(@Param("id") UUID id);

    @Modifying
    @Query("delete from TutorRegistrationOtp o where o.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
