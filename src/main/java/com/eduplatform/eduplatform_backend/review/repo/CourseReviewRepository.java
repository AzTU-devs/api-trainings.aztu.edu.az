package com.eduplatform.eduplatform_backend.review.repo;

import com.eduplatform.eduplatform_backend.review.domain.CourseReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CourseReviewRepository extends JpaRepository<CourseReview, UUID> {

    Optional<CourseReview> findByCourseIdAndUserId(UUID courseId, UUID userId);

    Page<CourseReview> findAllByCourseIdAndVisibleTrueOrderByCreatedAtDesc(UUID courseId, Pageable pageable);

    @Query("""
           select coalesce(avg(r.rating), 0), count(r)
           from CourseReview r
           where r.course.id = :courseId and r.visible = true
           """)
    Object[] aggregateRating(@Param("courseId") UUID courseId);
}
