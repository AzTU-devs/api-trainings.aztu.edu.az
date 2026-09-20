package com.eduplatform.eduplatform_backend.course.repo;

import com.eduplatform.eduplatform_backend.analytics.repo.AnalyticsViews.TopCategoryView;
import com.eduplatform.eduplatform_backend.analytics.repo.AnalyticsViews.TopCourseView;
import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CourseRepository extends JpaRepository<Course, UUID>, JpaSpecificationExecutor<Course>,
        CourseCatalogRepository {

    Optional<Course> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Page<Course> findAllByStatus(CourseStatus status, Pageable pageable);

    Page<Course> findAllByCourseTypeAndStatus(CourseType courseType, CourseStatus status, Pageable pageable);

    Page<Course> findAllByTutorId(UUID tutorId, Pageable pageable);

    /**
     * A tutor's own courses with optional status and free-text filters, both applied in SQL.
     *
     * <p>The portal's course list used to fetch one page and filter it by title in the
     * browser, so a search only ever looked at the ten rows already on screen — a tutor with
     * three pages of courses could not find one by name. This is the server-side counterpart.
     *
     * <p>A {@link Specification}, not a {@code @Query}:
     * the static form had to guard the text filter with {@code (:q is null or ...)}, which
     * binds a null String whenever there is no search. Hibernate 6 sends that null as
     * {@code bytea}, Postgres rejects {@code lower(bytea)}, and every list without {@code q}
     * — the dashboard's course list and approvals page — was a 500, in the page query and in
     * the count query Spring Data derives from it. Here a predicate exists only when its
     * filter is supplied, so neither the page query nor the count Spring Data derives from
     * the same Specification ever binds a null.
     *
     * <p>Ordering is fixed here rather than left to the caller's {@code Pageable}, because
     * without a deterministic order Postgres may return a row on two different pages and omit
     * another entirely. Only the caller's page and size are used.
     *
     * <p>{@code id} is the tiebreaker, and it is what actually makes the order
     * deterministic: {@code createdAt} alone is not unique — seeded, bulk-imported or
     * scripted courses routinely share a timestamp — and for ties Postgres is free to order
     * the two differently between the page-0 and page-1 queries. One row then appears twice
     * and another never appears. {@code CourseCatalogRepositoryImpl} appends the same
     * tiebreaker for the same reason.
     *
     * @param status null for every status
     * @param q      null or blank for no text filter
     */
    default Page<Course> searchTutorUserCourses(UUID userId, CourseStatus status, String q, Pageable pageable) {
        Specification<Course> spec = TutorCourseSearch.of(userId, status, q);
        if (pageable.isUnpaged()) {
            return new PageImpl<>(findAll(spec, TutorCourseSearch.NEWEST_FIRST));
        }
        return findAll(spec, PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), TutorCourseSearch.NEWEST_FIRST));
    }

    Page<Course> findAllByStatusOrderByCreatedAtDesc(CourseStatus status, Pageable pageable);

    long countByStatus(CourseStatus status);

    /** True if {@code mediaId} is the thumbnail or trailer of a PUBLISHED course (a public marketing asset). */
    @Query("""
           select case when count(c) > 0 then true else false end
           from Course c
           where c.status = 'PUBLISHED' and (c.thumbnail.id = :mediaId or c.trailer.id = :mediaId)
           """)
    boolean isPublishedCourseAsset(@Param("mediaId") UUID mediaId);

    @Query("select count(c) from Course c where c.tutor.user.id = :userId")
    long countByTutorUser(@Param("userId") UUID userId);

    @Query("select count(c) from Course c where c.tutor.user.id = :userId and c.status = :status")
    long countByTutorUserAndStatus(@Param("userId") UUID userId, @Param("status") CourseStatus status);

    @Query("""
           select c.id as id, c.title as title, c.enrolledCount as enrolledCount
           from Course c where c.status = :status order by c.enrolledCount desc
           """)
    List<TopCourseView> findTopCourses(@Param("status") CourseStatus status, Pageable pageable);

    @Query("""
           select cat.id as id, cat.name as name, count(c.id) as courseCount
           from Course c join c.categories cat
           where c.status = :status
           group by cat.id, cat.name
           order by count(c.id) desc
           """)
    List<TopCategoryView> findTopCategories(@Param("status") CourseStatus status, Pageable pageable);

    @Modifying
    @Query("update Course c set c.enrolledCount = c.enrolledCount + 1 where c.id = :id")
    int incrementEnrolledCount(@Param("id") UUID id);

    @Modifying
    @Query("update Course c set c.enrolledCount = c.enrolledCount - 1 where c.id = :id and c.enrolledCount > 0")
    int decrementEnrolledCount(@Param("id") UUID id);
}
