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

    @Modifying
    @Query("update OfflineCourseDetails d set d.enrolledCount = d.enrolledCount + 1 where d.courseId = :courseId")
    int incrementEnrolledCount(@Param("courseId") UUID courseId);

    @Modifying
    @Query("update OfflineCourseDetails d set d.enrolledCount = d.enrolledCount - 1 where d.courseId = :courseId and d.enrolledCount > 0")
    int decrementEnrolledCount(@Param("courseId") UUID courseId);
}
