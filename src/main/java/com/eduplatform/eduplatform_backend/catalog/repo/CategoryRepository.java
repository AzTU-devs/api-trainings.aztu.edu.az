package com.eduplatform.eduplatform_backend.catalog.repo;

import com.eduplatform.eduplatform_backend.catalog.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
