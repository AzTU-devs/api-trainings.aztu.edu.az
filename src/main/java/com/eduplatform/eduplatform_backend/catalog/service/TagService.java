package com.eduplatform.eduplatform_backend.catalog.service;

import com.eduplatform.eduplatform_backend.catalog.domain.Tag;
import com.eduplatform.eduplatform_backend.catalog.repo.TagRepository;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TagService {

    private final TagRepository repo;

    public TagService(TagRepository repo) {
        this.repo = repo;
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
        return repo.save(t);
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
        t.setSlug(slug);
        t.setName(name);
        return repo.save(t);
    }

    @Transactional
    public void delete(UUID id) {
        Tag t = repo.findById(id).orElseThrow(
                () -> Errors.notFound("TAG_NOT_FOUND", "Tag does not exist"));
        repo.delete(t);   // hard delete: tags have no soft-delete column
    }
}
