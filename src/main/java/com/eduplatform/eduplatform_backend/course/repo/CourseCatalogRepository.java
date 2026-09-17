package com.eduplatform.eduplatform_backend.course.repo;

import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The catalogue query, as a Spring Data fragment of {@link CourseRepository}.
 *
 * <p>It cannot be a {@code @Query} method: the relevance predicate needs Postgres'
 * {@code @@} operator, the predicates have to be emitted per request to stay
 * index-friendly, and {@code Sort} is not applicable to a native {@code @Query}.
 * See {@code CourseCatalogRepositoryImpl}.
 */
public interface CourseCatalogRepository {

    /**
     * One page of courses in {@code status} matching every constraint in {@code filter},
     * filtered, ordered, counted and paginated in SQL.
     */
    Page<Course> browseCatalog(CourseStatus status, CourseCatalogFilter filter, Pageable pageable);
}
