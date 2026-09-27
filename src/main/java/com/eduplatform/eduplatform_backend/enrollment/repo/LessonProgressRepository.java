package com.eduplatform.eduplatform_backend.enrollment.repo;

import com.eduplatform.eduplatform_backend.enrollment.domain.LessonProgress;
import com.eduplatform.eduplatform_backend.enrollment.domain.LessonProgressId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LessonProgressRepository extends JpaRepository<LessonProgress, LessonProgressId> {

    List<LessonProgress> findAllByEnrollmentId(UUID enrollmentId);

    /**
     * Completed lessons of the enrolment that still exist in this course. The joins go through
     * the lesson and its module, whose soft-delete restrictions leave deleted ones out, so the
     * count matches LessonRepository.countByCourseId, the total it is divided by.
     */
    @Query("""
           select count(lp) from LessonProgress lp
             join lp.lesson l
             join l.module m
           where lp.enrollment.id = :enrollmentId
             and m.course.id = :courseId
             and lp.status = com.eduplatform.eduplatform_backend.common.enums.LessonProgressStatus.COMPLETED
           """)
    long countCompletedLiveLessons(@Param("enrollmentId") UUID enrollmentId, @Param("courseId") UUID courseId);
}
