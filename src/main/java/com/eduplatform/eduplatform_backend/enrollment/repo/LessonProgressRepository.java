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

    @Query("""
           select count(lp) from LessonProgress lp
           where lp.enrollment.id = :enrollmentId and lp.status = 'COMPLETED'
           """)
    long countCompletedByEnrollment(@Param("enrollmentId") UUID enrollmentId);
}
