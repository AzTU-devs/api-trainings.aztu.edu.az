package com.eduplatform.eduplatform_backend.catalog.service;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.catalog.domain.Tag;
import com.eduplatform.eduplatform_backend.catalog.repo.TagRepository;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Course tags. Every change is audited, as category changes are: tag:manage is an admin power
 * whose effects every course page shows, and until now a tag could be renamed or deleted — which
 * takes it off every course carrying it — without any trace of who did it.
 */
@Service
public class TagService {

    private final TagRepository repo;
    private final AuditService audit;

    public TagService(TagRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Tag> all() {
        return repo.findAll();
    }

    @Transactional
    public Tag create(String slug, String name) {
        repo.findBySlug(slug).ifPresent(t -> {
            throw Errors.conflict("SLUG_ALREADY_EXISTS", "Tag slug '" + slug + "' is taken");
        });
        Tag t = Tag.builder().slug(slug).name(name).build();
        t.setId(UUID.randomUUID());
        Tag saved = repo.save(t);
        audit.record(AuditService.Actions.CREATE, "TAG", saved.getId(), null, auditableFields(saved));
        return saved;
    }

    @Transactional
    public Tag update(UUID id, String slug, String name) {
        Tag t = repo.findById(id).orElseThrow(
                () -> Errors.notFound("TAG_NOT_FOUND", "Tag does not exist"));
        if (!t.getSlug().equals(slug)) {
            repo.findBySlug(slug).ifPresent(other -> {
                throw Errors.conflict("SLUG_ALREADY_EXISTS", "Tag slug '" + slug + "' is taken");
            });
        }
        Map<String, Object> before = auditableFields(t);
        t.setSlug(slug);
        t.setName(name);
        Tag saved = repo.save(t);
        audit.record(AuditService.Actions.UPDATE, "TAG", saved.getId(), before, auditableFields(saved));
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        Tag t = repo.findById(id).orElseThrow(
                () -> Errors.notFound("TAG_NOT_FOUND", "Tag does not exist"));
        Map<String, Object> before = auditableFields(t);
        repo.delete(t);   // hard delete: tags have no soft-delete column
        // The snapshot is all that is left of a hard-deleted tag.
        audit.record(AuditService.Actions.DELETE, "TAG", id, before, null);
    }

    private static Map<String, Object> auditableFields(Tag t) {
        return AuditService.snapshot("slug", t.getSlug(), "name", t.getName());
    }
}
