package com.eduplatform.eduplatform_backend.review.repo;

import com.eduplatform.eduplatform_backend.review.domain.CourseReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CourseReviewRepository extends JpaRepository<CourseReview, UUID> {

    Optional<CourseReview> findByCourseIdAndUserId(UUID courseId, UUID userId);

    /**
     * Brings each review's author back in the same select, because ReviewMapper builds
     * {@code authorName} from the user after the transaction has closed (open-in-view=false).
     * A to-one join, so the page is still limited in SQL.
     *
     * <p>No order of its own: the pageable's sort is the whole ORDER BY. An order in the method
     * name would always come first, so a client's {@code sort} could never take effect.
     * Callers reachable anonymously must hand in only a whitelisted sort — see
     * {@code ReviewService#forCourse}.
     */
    @EntityGraph(attributePaths = "user")
    Page<CourseReview> findAllByCourseIdAndVisibleTrue(UUID courseId, Pageable pageable);

    /**
     * Average rating and count of a course's visible reviews: always exactly one row, since the
     * query aggregates without GROUP BY. Declared as a list because Spring Data JPA runs a query
     * method returning {@code Object[]} as a collection query, which hands the row back wrapped
     * in a second array, so reading it as {@code [avg, count]} failed with a ClassCastException.
     */
    @Query("""
           select coalesce(avg(r.rating), 0), count(r)
           from CourseReview r
           where r.course.id = :courseId and r.visible = true
           """)
    List<Object[]> aggregateRating(@Param("courseId") UUID courseId);
}
