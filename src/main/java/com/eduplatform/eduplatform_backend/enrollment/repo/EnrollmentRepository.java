package com.eduplatform.eduplatform_backend.enrollment.repo;

import com.eduplatform.eduplatform_backend.analytics.repo.AnalyticsViews.TrendPointView;
import com.eduplatform.eduplatform_backend.common.enums.EnrollmentStatus;
import com.eduplatform.eduplatform_backend.enrollment.domain.Enrollment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

    Optional<Enrollment> findByUserIdAndCourseId(UUID userId, UUID courseId);

    boolean existsByUserIdAndCourseId(UUID userId, UUID courseId);

    /**
     * Whether {@code mediaId} is the photo of a participant holding a place on a course that
     * {@code tutorUserId} teaches, as its editor or on its roster.
     */
    @Query("""
           select case when count(e) > 0 then true else false end
           from Enrollment e
           where e.user.avatar.id = :mediaId
             and e.status in (com.eduplatform.eduplatform_backend.common.enums.EnrollmentStatus.ACTIVE,
                              com.eduplatform.eduplatform_backend.common.enums.EnrollmentStatus.COMPLETED)
             and exists (select 1 from Course c left join c.tutors t
                         where c.id = e.course.id
                           and (c.tutor.user.id = :tutorUserId or t.user.id = :tutorUserId))
           """)
    boolean isAvatarOfStudentTaughtBy(@Param("mediaId") UUID mediaId, @Param("tutorUserId") UUID tutorUserId);

    /** An account's enrolments in any of {@code statuses}; see EnrollmentService.releasePlacesOf. */
    List<Enrollment> findAllByUserIdAndStatusIn(UUID userId, java.util.Collection<EnrollmentStatus> statuses);

    /** Whether the user holds an enrolment in one of {@code statuses}; see CourseAccess.PLACE_HOLDING. */
    boolean existsByUserIdAndCourseIdAndStatusIn(UUID userId, UUID courseId,
                                                 java.util.Collection<EnrollmentStatus> statuses);

    /**
     * Brings each enrollment's course back in the same select, because EnrollmentMapper reads
     * {@code course.title} after the transaction has closed (open-in-view=false). The course's
     * online/offline details are inverse one-to-ones, which Hibernate loads eagerly with two
     * extra selects per course unless they are joined here too. All of these are to-one joins,
     * so the page is still limited in SQL.
     */
    @EntityGraph(attributePaths = {"course", "course.onlineDetails", "course.offlineDetails"})
    Page<Enrollment> findAllByUserId(UUID userId, Pageable pageable);

    /**
     * A course's roster for the admin participants screen. The user comes back in the same
     * select because every row renders a name and an email, which would otherwise be one
     * query per participant; the avatar is left lazy and read by id alone.
     */
    @EntityGraph(attributePaths = "user")
    Page<Enrollment> findAllByCourseId(UUID courseId, Pageable pageable);

    /** As {@link #findAllByCourseId}, narrowed to one enrolment status. */
    @EntityGraph(attributePaths = "user")
    Page<Enrollment> findAllByCourseIdAndStatus(UUID courseId, EnrollmentStatus status, Pageable pageable);

    long countByCourseId(UUID courseId);

    long countByEnrolledAtAfter(Instant since);

    long countByStatus(EnrollmentStatus status);

    /** Distinct count of students (users) holding an enrollment in the given status. */
    @Query("select count(distinct e.user.id) from Enrollment e where e.status = :status")
    long countDistinctStudentsByStatus(@Param("status") EnrollmentStatus status);

    /**
     * Distinct count of students (users) enrolled across any course the given tutor user teaches,
     * as its authorised editor or on its roster.
     */
    @Query("""
           select count(distinct e.user.id) from Enrollment e join e.course c
           where c.tutor.user.id = :tutorUserId
              or exists (select 1 from c.tutors t where t.user.id = :tutorUserId)
           """)
    long countDistinctStudentsByTutorUser(@Param("tutorUserId") UUID tutorUserId);

    /**
     * Daily enrollment counts since {@code since}, ordered ascending by day (yyyy-MM-dd), the days
     * being those of {@code zone} (an IANA name such as Asia/Baku) rather than of the database
     * session, which runs in UTC.
     */
    @Query(value = """
           select to_char(date_trunc('day', enrolled_at at time zone :zone), 'YYYY-MM-DD') as day, count(*) as cnt
           from enrollments
           where deleted_at is null and enrolled_at >= :since
           group by 1
           order by 1
           """, nativeQuery = true)
    List<TrendPointView> findEnrollmentTrend(@Param("since") Instant since, @Param("zone") String zone);

    /**
     * Students enrolled in the courses a tutor teaches (as editor or on the roster), aggregated
     * per student. {@code search} matches
     * email / first / last name (case-insensitive); {@code courseId} narrows to one course.
     */
    @Query(value = """
           select u.id as userId, u.firstName as firstName, u.lastName as lastName,
                  u.email as email, u.avatar.id as avatarId,
                  sum(case when e.status = :activeStatus then 1 else 0 end) as activeEnrollments,
                  count(e) as totalEnrollments,
                  avg(e.progressPercent) as averageProgressPct,
                  max(e.lastAccessedAt) as lastActivityAt
           from Enrollment e
             join e.user u
             join e.course c
           where (c.tutor.user.id = :tutorUserId
                  or exists (select 1 from c.tutors t where t.user.id = :tutorUserId))
             and (:search is null
                  or lower(u.email) like lower(concat('%', cast(:search as string), '%'))
                  or lower(u.firstName) like lower(concat('%', cast(:search as string), '%'))
                  or lower(u.lastName) like lower(concat('%', cast(:search as string), '%')))
             and (:courseId is null or c.id = :courseId)
           group by u.id, u.firstName, u.lastName, u.email, u.avatar.id
           """,
           countQuery = """
           select count(distinct u.id)
           from Enrollment e join e.user u join e.course c
           where (c.tutor.user.id = :tutorUserId
                  or exists (select 1 from c.tutors t where t.user.id = :tutorUserId))
             and (:search is null
                  or lower(u.email) like lower(concat('%', cast(:search as string), '%'))
                  or lower(u.firstName) like lower(concat('%', cast(:search as string), '%'))
                  or lower(u.lastName) like lower(concat('%', cast(:search as string), '%')))
             and (:courseId is null or c.id = :courseId)
           """)
    Page<TutorStudentRow> findTutorStudents(@Param("tutorUserId") UUID tutorUserId,
                                            @Param("search") String search,
                                            @Param("courseId") UUID courseId,
                                            @Param("activeStatus") EnrollmentStatus activeStatus,
                                            Pageable pageable);
}
