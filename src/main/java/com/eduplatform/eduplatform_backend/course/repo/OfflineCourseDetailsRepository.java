package com.eduplatform.eduplatform_backend.course.repo;

import com.eduplatform.eduplatform_backend.course.domain.OfflineCourseDetails;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OfflineCourseDetailsRepository extends JpaRepository<OfflineCourseDetails, UUID> {

    /**
     * Takes one seat if one is left, atomically: the condition and the increment are one UPDATE,
     * so two requests for the last seat cannot both succeed. Returns 0 when the course is full.
     */
    @Modifying
    @Query("""
           update OfflineCourseDetails d set d.enrolledCount = d.enrolledCount + 1
           where d.courseId = :courseId and d.enrolledCount < d.studentLimit
           """)
    int claimSeat(@Param("courseId") UUID courseId);

    /** Takes a seat whatever the limit — for an administrator seating somebody by hand. */
    @Modifying
    @Query("update OfflineCourseDetails d set d.enrolledCount = d.enrolledCount + 1 where d.courseId = :courseId")
    int incrementEnrolledCount(@Param("courseId") UUID courseId);

    @Modifying
    @Query("update OfflineCourseDetails d set d.enrolledCount = d.enrolledCount - 1 where d.courseId = :courseId and d.enrolledCount > 0")
    int decrementEnrolledCount(@Param("courseId") UUID courseId);

    /**
     * The seats taken, counted from the enrolments as CourseRepository.recountEnrolled counts the
     * course's places; a no-op for an online course, which has no row here.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
           update offline_course_details set enrolled_count =
               (select count(*) from enrollments e
                  join users u on u.id = e.user_id and u.deleted_at is null
                 where e.course_id = :courseId and e.deleted_at is null
                   and e.status in ('ACTIVE', 'COMPLETED'))
           where course_id = :courseId
           """, nativeQuery = true)
    int recountEnrolled(@Param("courseId") UUID courseId);
}
