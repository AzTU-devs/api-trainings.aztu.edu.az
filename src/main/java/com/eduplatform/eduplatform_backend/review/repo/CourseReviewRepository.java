package com.eduplatform.eduplatform_backend.review.repo;

import com.eduplatform.eduplatform_backend.review.domain.CourseReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // Reviews by an account that has been deleted are left out of every list and of the rating:
    // deleting an account retires its reviews (ReviewService.retireReviewsOf), and these
    // conditions keep a row that escaped that — one deleted before it existed, say — from
    // counting where it can no longer be seen. The author's name comes from
    // CourseReview.authorName, so no list loads the accounts.

    /**
     * A course's visible reviews.
     *
     * <p>No order of its own: the pageable's sort is the whole ORDER BY. An order in the query
     * would always come first, so a client's {@code sort} could never take effect. Callers
     * reachable anonymously must hand in only a whitelisted sort — see
     * {@code ReviewService#forCourse}.
     */
    @Query(value = """
           select r from CourseReview r
           where r.course.id = :courseId and r.visible = true
             and exists (select 1 from User u where u.id = r.user.id and u.deletedAt is null)
           """,
           countQuery = """
           select count(r) from CourseReview r
           where r.course.id = :courseId and r.visible = true
             and exists (select 1 from User u where u.id = r.user.id and u.deletedAt is null)
           """)
    Page<CourseReview> findVisibleByCourseId(@Param("courseId") UUID courseId, Pageable pageable);

    /** Reviews for the moderation screen, hidden ones included; either filter may be null. */
    @Query(value = """
           select r from CourseReview r
           where (:courseId is null or r.course.id = :courseId)
             and (:visible is null or r.visible = :visible)
             and exists (select 1 from User u where u.id = r.user.id and u.deletedAt is null)
           order by r.createdAt desc, r.id desc
           """,
           countQuery = """
           select count(r) from CourseReview r
           where (:courseId is null or r.course.id = :courseId)
             and (:visible is null or r.visible = :visible)
             and exists (select 1 from User u where u.id = r.user.id and u.deletedAt is null)
           """)
    Page<CourseReview> searchForModeration(@Param("courseId") UUID courseId,
                                          @Param("visible") Boolean visible,
                                          Pageable pageable);

    /** Every live review an account wrote, for retiring them with the account. */
    List<CourseReview> findAllByUserId(UUID userId);

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
             and exists (select 1 from User u where u.id = r.user.id and u.deletedAt is null)
           """)
    List<Object[]> aggregateRating(@Param("courseId") UUID courseId);
}
