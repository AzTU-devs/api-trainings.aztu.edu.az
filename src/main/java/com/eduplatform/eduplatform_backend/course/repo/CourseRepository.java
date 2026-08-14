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
public interface CourseRepository extends JpaRepository<Course, UUID> {

    Optional<Course> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Page<Course> findAllByStatus(CourseStatus status, Pageable pageable);

    Page<Course> findAllByCourseTypeAndStatus(CourseType courseType, CourseStatus status, Pageable pageable);

    /** Public catalog browse with optional type + category filters. */
    @Query("""
           select c from Course c
           where c.status = :status
             and (:type is null or c.courseType = :type)
             and (:categoryId is null or exists (select 1 from c.categories cat where cat.id = :categoryId))
           order by c.publishedAt desc nulls last
           """)
    Page<Course> browseCatalog(@Param("status") CourseStatus status,
                               @Param("type") CourseType type,
                               @Param("categoryId") UUID categoryId,
                               Pageable pageable);

    Page<Course> findAllByTutorId(UUID tutorId, Pageable pageable);

    /** A tutor's own courses (all statuses), resolved by the owning user id. */
    @Query("select c from Course c where c.tutor.user.id = :userId")
    Page<Course> findAllByTutorUser(@Param("userId") UUID userId, Pageable pageable);

    @Query("select c from Course c where c.tutor.user.id = :userId and c.status = :status")
    Page<Course> findAllByTutorUserAndStatus(@Param("userId") UUID userId,
                                             @Param("status") CourseStatus status,
                                             Pageable pageable);

    Page<Course> findAllByStatusOrderByCreatedAtDesc(CourseStatus status, Pageable pageable);

    /** Full-text search over published courses; falls back to ILIKE when GIN cannot match. */
    @Query(value = """
           select * from courses c
           where c.deleted_at is null
             and c.status = 'PUBLISHED'
             and (
               to_tsvector('simple', c.title || ' ' || coalesce(c.subtitle,'') || ' ' || coalesce(c.description,''))
                 @@ plainto_tsquery('simple', :q)
               or c.title ilike concat('%', :q, '%')
             )
           order by c.published_at desc nulls last
           """,
           countQuery = """
           select count(*) from courses c
           where c.deleted_at is null
             and c.status = 'PUBLISHED'
             and (
               to_tsvector('simple', c.title || ' ' || coalesce(c.subtitle,'') || ' ' || coalesce(c.description,''))
                 @@ plainto_tsquery('simple', :q)
               or c.title ilike concat('%', :q, '%')
             )
           """,
           nativeQuery = true)
    Page<Course> search(@Param("q") String query, Pageable pageable);

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
