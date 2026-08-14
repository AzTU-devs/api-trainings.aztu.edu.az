package com.eduplatform.eduplatform_backend.catalog.repo;

import com.eduplatform.eduplatform_backend.catalog.domain.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TagRepository extends JpaRepository<Tag, UUID> {

    Optional<Tag> findBySlug(String slug);

    List<Tag> findAllBySlugIn(List<String> slugs);
}
