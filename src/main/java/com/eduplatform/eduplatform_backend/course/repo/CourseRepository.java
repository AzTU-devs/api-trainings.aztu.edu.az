package com.eduplatform.eduplatform_backend.course.repo;

import com.eduplatform.eduplatform_backend.analytics.repo.AnalyticsViews.TopCategoryView;
import com.eduplatform.eduplatform_backend.analytics.repo.AnalyticsViews.TopCourseView;
import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CourseRepository extends JpaRepository<Course, UUID>, CourseCatalogRepository {

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
     * <p>ILIKE rather than the catalog's full-text index: this searches a single tutor's own
     * courses including drafts, where the row count is small and substring matching on a
     * partial title ("mach" finding "Machine Learning") is more useful than stemmed word
     * matching. The public catalog keeps its GIN/tsvector path.
     *
     * <p>Ordering is fixed here rather than left to the caller's {@code Pageable}, because
     * without a deterministic order Postgres may return a row on two different pages and omit
     * another entirely. Callers pass page and size only.
     *
     * <p>{@code c.id} is the tiebreaker, and it is what actually makes the order
     * deterministic: {@code createdAt} alone is not unique — seeded, bulk-imported or
     * scripted courses routinely share a timestamp — and for ties Postgres is free to order
     * the two differently between the page-0 and page-1 queries. One row then appears twice
     * and another never appears. {@code CourseCatalogRepositoryImpl} appends the same
     * tiebreaker for the same reason.
     */
    @Query("""
           select c from Course c
           where c.tutor.user.id = :userId
             and (:status is null or c.status = :status)
             and (:q is null or (
                    lower(c.title) like lower(concat('%', :q, '%'))
                 or lower(coalesce(c.subtitle, '')) like lower(concat('%', :q, '%'))
                 or lower(c.slug) like lower(concat('%', :q, '%'))
             ))
           order by c.createdAt desc, c.id desc
           """)
    Page<Course> searchTutorUserCourses(@Param("userId") UUID userId,
                                        @Param("status") CourseStatus status,
                                        @Param("q") String q,
                                        Pageable pageable);

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
