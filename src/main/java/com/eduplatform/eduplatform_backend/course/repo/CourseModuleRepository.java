package com.eduplatform.eduplatform_backend.course.repo;

import com.eduplatform.eduplatform_backend.course.domain.CourseModule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CourseModuleRepository extends JpaRepository<CourseModule, UUID> {

    List<CourseModule> findAllByCourseIdOrderByOrderIndexAsc(UUID courseId);

    /** Whether another live module of the course holds {@code orderIndex}; see CourseContentService. */
    boolean existsByCourseIdAndOrderIndexAndIdNot(UUID courseId, int orderIndex, UUID id);

    long countByCourseId(UUID courseId);

    /**
     * One past the highest position any module of the course has ever had. Native, so that
     * deleted modules count too: their positions stay taken in the unique index of databases
     * that predate V16, and reusing a deleted module's slot there is exactly the 409 this avoids.
     */
    @Query(value = "select coalesce(max(order_index), -1) + 1 from course_modules where course_id = :courseId",
            nativeQuery = true)
    int nextOrderIndex(@Param("courseId") UUID courseId);

    /** Row-locks the module until the transaction ends, so two lesson adds cannot share a position. */
    @Query(value = "select 1 from course_modules where id = :id for update", nativeQuery = true)
    Integer lockForContentChange(@Param("id") UUID id);
}
