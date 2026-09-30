package com.eduplatform.eduplatform_backend.tutor.repo;

import com.eduplatform.eduplatform_backend.common.enums.TutorApprovalStatus;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TutorProfileRepository extends JpaRepository<TutorProfile, UUID> {

    Optional<TutorProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    // ── Profiles whose account still exists ────────────────────────────────
    // An account deleted before deleting retired its expert profile (V21) can leave a live
    // profile pointing at a user no read can load; mapping one threw, and a single such row took
    // the whole Tutors tab down with it. The expert lists and the public page are about people
    // who can still sign in, so they leave those profiles out. The courses that still name one
    // read the name through TutorProfile.displayName instead, and keep working.

    // `join fetch`: every caller maps the account's name for each row, and a lazy one-to-one
    // loaded one account per row — up to 100 queries for a page of the public directory.
    @Query(value = """
           select t from TutorProfile t join fetch t.user u
           where t.approvalStatus = :status and u.deletedAt is null
           """,
           countQuery = """
           select count(t) from TutorProfile t join t.user u
           where t.approvalStatus = :status and u.deletedAt is null
           """)
    Page<TutorProfile> findAllWithLiveAccountByApprovalStatus(@Param("status") TutorApprovalStatus status,
                                                             Pageable pageable);

    @Query("select t from TutorProfile t join t.user u where t.id = :id and u.deletedAt is null")
    Optional<TutorProfile> findWithLiveAccountById(@Param("id") UUID id);

    @Query("""
           select count(t) from TutorProfile t join t.user u
           where t.approvalStatus = :status and u.deletedAt is null
           """)
    long countWithLiveAccountByApprovalStatus(@Param("status") TutorApprovalStatus status);

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

    /**
     * Whether any course names this profile, as its authorised editor or on its roster, whatever
     * the course's status. Native so the answer does not depend on which rows an entity
     * restriction would hide.
     */
    @Query(value = """
           select exists (
             select 1 from courses c
             where c.deleted_at is null
               and (c.tutor_id = :tutorId
                    or exists (select 1 from course_tutors ct where ct.course_id = c.id and ct.tutor_id = :tutorId)))
           """, nativeQuery = true)
    boolean isNamedOnAnyCourse(@Param("tutorId") UUID tutorId);

    /** Clears a deleted file from every profile that has it as its portrait. */
    @Modifying
    @Query(value = "update tutor_profiles set avatar_media_id = null where avatar_media_id = :mediaId",
            nativeQuery = true)
    int detachAvatar(@Param("mediaId") UUID mediaId);

    /** Whether any room booking, in any status, was requested by this profile. */
    @Query(value = """
           select exists (select 1 from room_bookings b where b.tutor_id = :tutorId and b.deleted_at is null)
           """, nativeQuery = true)
    boolean hasRoomBookings(@Param("tutorId") UUID tutorId);
}
