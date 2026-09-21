package com.eduplatform.eduplatform_backend.tutor.repo;

import com.eduplatform.eduplatform_backend.common.enums.TutorApprovalStatus;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TutorProfileRepository extends JpaRepository<TutorProfile, UUID> {

    Optional<TutorProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    Page<TutorProfile> findAllByApprovalStatus(TutorApprovalStatus status, Pageable pageable);

    long countByApprovalStatus(TutorApprovalStatus status);

    /**
     * Whether a file is the portrait of an APPROVED expert, which the public expert page shows to
     * anyone. A pending, rejected or suspended applicant's portrait stays private, as does that of
     * a deleted profile, which the entity's soft-delete restriction leaves out of this query.
     */
    @Query("""
           select case when count(t) > 0 then true else false end
           from TutorProfile t
           where t.avatar.id = :mediaId and t.approvalStatus = 'APPROVED'
           """)
    boolean isApprovedTutorAvatar(@Param("mediaId") UUID mediaId);

    /**
     * {@link #isApprovedTutorAvatar} for a signed-in viewer, who may also see the portrait on their
     * own profile whatever its status — including one an admin uploaded for them, which they do
     * not own.
     */
    @Query("""
           select case when count(t) > 0 then true else false end
           from TutorProfile t
           where t.avatar.id = :mediaId and (t.approvalStatus = 'APPROVED' or t.user.id = :userId)
           """)
    boolean isTutorAvatarVisibleTo(@Param("mediaId") UUID mediaId, @Param("userId") UUID userId);
}
