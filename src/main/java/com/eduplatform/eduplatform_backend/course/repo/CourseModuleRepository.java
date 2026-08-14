package com.eduplatform.eduplatform_backend.course.repo;

import com.eduplatform.eduplatform_backend.course.domain.CourseModule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CourseModuleRepository extends JpaRepository<CourseModule, UUID> {

    List<CourseModule> findAllByCourseIdOrderByOrderIndexAsc(UUID courseId);

    long countByCourseId(UUID courseId);
}
