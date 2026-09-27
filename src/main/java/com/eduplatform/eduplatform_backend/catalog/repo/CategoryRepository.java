package com.eduplatform.eduplatform_backend.catalog.repo;

import com.eduplatform.eduplatform_backend.catalog.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Category> findAllByParentIsNullOrderBySortOrderAscNameAsc();

    List<Category> findAllByParentIdOrderBySortOrderAscNameAsc(UUID parentId);

    List<Category> findAllByActiveTrue();

    List<Category> findAllByParentIsNullAndActiveTrueOrderBySortOrderAscNameAsc();

    List<Category> findAllByParentIdAndActiveTrueOrderBySortOrderAscNameAsc(UUID parentId);

    List<Category> findAllByOrderBySortOrderAscNameAsc();

    @Query("select count(c) from Category c where c.parent.id = :id")
    long countLiveChildren(@Param("id") UUID id);

    /** Courses, in any status, filed under the category. Native: the link table has no entity. */
    @Query(value = """
           select count(*) from course_categories cc join courses c on c.id = cc.course_id
           where cc.category_id = :id and c.deleted_at is null
           """, nativeQuery = true)
    long countLiveCourses(@Param("id") UUID id);

    /** Expert profiles that list the category among their areas. */
    @Query(value = """
           select count(*) from tutor_expertises te join tutor_profiles t on t.id = te.tutor_id
           where te.category_id = :id and t.deleted_at is null
           """, nativeQuery = true)
    long countLiveExperts(@Param("id") UUID id);
}
