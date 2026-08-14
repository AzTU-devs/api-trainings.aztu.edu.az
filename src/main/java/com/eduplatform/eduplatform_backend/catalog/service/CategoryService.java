package com.eduplatform.eduplatform_backend.catalog.service;

import com.eduplatform.eduplatform_backend.catalog.domain.Category;
import com.eduplatform.eduplatform_backend.catalog.repo.CategoryRepository;
import com.eduplatform.eduplatform_backend.catalog.web.dto.CategoryUpsertRequest;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CategoryService {

    private final CategoryRepository repo;

    public CategoryService(CategoryRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Category get(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> Errors.notFound("CATEGORY_NOT_FOUND", "Category does not exist"));
    }

    @Transactional(readOnly = true)
    public List<Category> roots() {
        return repo.findAllByParentIsNullOrderBySortOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<Category> children(UUID parentId) {
        return repo.findAllByParentIdOrderBySortOrderAscNameAsc(parentId);
    }

    @Transactional
    public Category create(CategoryUpsertRequest req) {
        if (repo.existsBySlug(req.slug())) {
            throw Errors.conflict("SLUG_ALREADY_EXISTS", "Category slug '" + req.slug() + "' is taken");
        }
        Category c = Category.builder()
                .slug(req.slug())
                .name(req.name())
                .description(req.description())
                .iconUrl(req.iconUrl())
                .sortOrder(req.sortOrder())
                .active(req.active() == null || req.active())
                .parent(req.parentId() == null ? null : get(req.parentId()))
                .build();
        c.setId(UUID.randomUUID());
        return repo.save(c);
    }

    @Transactional
    public Category update(UUID id, CategoryUpsertRequest req) {
        Category c = get(id);
        if (!c.getSlug().equals(req.slug()) && repo.existsBySlug(req.slug())) {
            throw Errors.conflict("SLUG_ALREADY_EXISTS", "Category slug '" + req.slug() + "' is taken");
        }
        c.setSlug(req.slug());
        c.setName(req.name());
        c.setDescription(req.description());
        c.setIconUrl(req.iconUrl());
        c.setSortOrder(req.sortOrder());
        if (req.active() != null) c.setActive(req.active());
        c.setParent(req.parentId() == null ? null : get(req.parentId()));
        return repo.save(c);
    }

    @Transactional
    public void delete(UUID id) {
        Category c = get(id);
        repo.delete(c);   // soft-delete via @SQLDelete
    }
}
