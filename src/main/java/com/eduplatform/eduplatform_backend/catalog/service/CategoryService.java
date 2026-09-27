package com.eduplatform.eduplatform_backend.catalog.service;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.catalog.domain.Category;
import com.eduplatform.eduplatform_backend.catalog.repo.CategoryRepository;
import com.eduplatform.eduplatform_backend.catalog.web.dto.CategoryUpsertRequest;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Categories. The public site sees active ones only ({@link #publicRoots}, {@link #publicChildren});
 * administrators see all of them, hidden ones included, through {@link #all}.
 */
@Service
public class CategoryService {

    private final CategoryRepository repo;
    private final AuditService audit;

    public CategoryService(CategoryRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Category get(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> Errors.notFound("CATEGORY_NOT_FOUND", "Category does not exist"));
    }

    /**
     * Top-level categories, hidden ones included. Kept for internal callers; the public endpoint
     * uses {@link #publicRoots}.
     */
    @Transactional(readOnly = true)
    public List<Category> roots() {
        return repo.findAllByParentIsNullOrderBySortOrderAscNameAsc();
    }

    /**
     * What the public site may list: active top-level categories. A category an admin marked
     * Hidden used to be served here all the same.
     */
    @Transactional(readOnly = true)
    public List<Category> publicRoots() {
        return repo.findAllByParentIsNullAndActiveTrueOrderBySortOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<Category> publicChildren(UUID parentId) {
        return repo.findAllByParentIdAndActiveTrueOrderBySortOrderAscNameAsc(parentId);
    }

    @Transactional(readOnly = true)
    public List<Category> children(UUID parentId) {
        return repo.findAllByParentIdOrderBySortOrderAscNameAsc(parentId);
    }

    /**
     * Every live category, flat, hidden ones included, for the admin Categories page. The page
     * used to read the public list, so it could show neither sub-categories nor — once the public
     * list is filtered — hidden ones.
     */
    @Transactional(readOnly = true)
    public List<Category> all() {
        List<Category> list = repo.findAllByOrderBySortOrderAscNameAsc();
        list.forEach(c -> { if (c.getParent() != null) c.getParent().getId(); });
        return list;
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
        Category saved = repo.save(c);
        audit.record(AuditService.Actions.CREATE, "CATEGORY", saved.getId(), null, auditableFields(saved));
        return saved;
    }

    @Transactional
    public Category update(UUID id, CategoryUpsertRequest req) {
        Category c = get(id);
        Map<String, Object> before = auditableFields(c);
        if (!c.getSlug().equals(req.slug()) && repo.existsBySlug(req.slug())) {
            throw Errors.conflict("SLUG_ALREADY_EXISTS", "Category slug '" + req.slug() + "' is taken");
        }
        Category parent = req.parentId() == null ? null : get(req.parentId());
        requireNoCycle(c, parent);
        c.setSlug(req.slug());
        c.setName(req.name());
        c.setDescription(req.description());
        c.setIconUrl(req.iconUrl());
        c.setSortOrder(req.sortOrder());
        if (req.active() != null) c.setActive(req.active());
        c.setParent(parent);
        Category saved = repo.save(c);
        audit.record(AuditService.Actions.UPDATE, "CATEGORY", saved.getId(), before, auditableFields(saved));
        return saved;
    }

    /**
     * Deletes a category nothing uses. The link rows of a soft-deleted category survive and its
     * restriction hides it, so deleting one in use quietly stripped it from published courses and
     * from experts' areas of expertise, while the catalogue's category filter kept matching the
     * old links; and a deleted parent left its children orphaned where no page could reach them.
     * Such a delete is refused with 409 and the counts, so the admin can move things first — or
     * hide the category instead, which takes it off the public site and keeps every link.
     */
    @Transactional
    public void delete(UUID id) {
        Category c = get(id);
        long children = repo.countLiveChildren(id);
        if (children > 0) {
            throw Errors.conflict("CATEGORY_HAS_CHILDREN",
                    "This category has " + children + " sub-categories; move or delete them first");
        }
        long courses = repo.countLiveCourses(id);
        long experts = repo.countLiveExperts(id);
        if (courses > 0 || experts > 0) {
            throw Errors.conflict("CATEGORY_IN_USE",
                    "This category is used by " + courses + " training(s) and " + experts
                            + " expert profile(s). Recategorise them first, or hide the category instead.");
        }
        repo.delete(c);   // soft-delete via @SQLDelete
        audit.record(AuditService.Actions.DELETE, "CATEGORY", id, auditableFields(c), null);
    }

    /** A category cannot be its own parent, directly or through its descendants. */
    private static void requireNoCycle(Category category, Category parent) {
        Set<UUID> seen = new HashSet<>();
        for (Category p = parent; p != null; p = p.getParent()) {
            if (p.getId().equals(category.getId()) || !seen.add(p.getId())) {
                throw Errors.badRequest("CATEGORY_CYCLE", "A category cannot be placed under itself or its own sub-category");
            }
        }
    }

    private static Map<String, Object> auditableFields(Category c) {
        return AuditService.snapshot(
                "slug", c.getSlug(), "name", c.getName(), "active", c.isActive(),
                "parentId", c.getParent() == null ? null : c.getParent().getId().toString(),
                "sortOrder", c.getSortOrder());
    }
}
