package com.eduplatform.eduplatform_backend.enrollment.repo;

import com.eduplatform.eduplatform_backend.analytics.repo.AnalyticsViews.TrendPointView;
import com.eduplatform.eduplatform_backend.common.enums.EnrollmentStatus;
import com.eduplatform.eduplatform_backend.enrollment.domain.Enrollment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    Page<Enrollment> findAllByUserId(UUID userId, Pageable pageable);

    Page<Enrollment> findAllByCourseId(UUID courseId, Pageable pageable);

    long countByCourseId(UUID courseId);

    long countByEnrolledAtAfter(Instant since);

    long countByStatus(EnrollmentStatus status);

    /** Distinct count of students (users) holding an enrollment in the given status. */
    @Query("select count(distinct e.user.id) from Enrollment e where e.status = :status")
    long countDistinctStudentsByStatus(@Param("status") EnrollmentStatus status);

    /** Distinct count of students (users) enrolled across any course owned by the given tutor user. */
    @Query("select count(distinct e.user.id) from Enrollment e where e.course.tutor.user.id = :tutorUserId")
    long countDistinctStudentsByTutorUser(@Param("tutorUserId") UUID tutorUserId);

    /** Daily enrollment counts since {@code since}, ordered ascending by day (yyyy-MM-dd). */
    @Query(value = """
           select to_char(date_trunc('day', enrolled_at), 'YYYY-MM-DD') as day, count(*) as cnt
           from enrollments
           where deleted_at is null and enrolled_at >= :since
           group by 1
           order by 1
           """, nativeQuery = true)
    List<TrendPointView> findEnrollmentTrend(@Param("since") Instant since);

    /**
     * Students enrolled in a tutor's courses, aggregated per student. {@code search} matches
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
           where c.tutor.user.id = :tutorUserId
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
           where c.tutor.user.id = :tutorUserId
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
