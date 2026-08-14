package com.eduplatform.eduplatform_backend.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Marker superclass for entities supporting soft delete.
 * Concrete entities should additionally annotate with:
 *   {@code @SQLDelete(sql = "UPDATE <table> SET deleted_at = now() WHERE id = ? AND version = ?")}
 *   {@code @SQLRestriction("deleted_at IS NULL")}
 * The trailing {@code AND version = ?} is required: these entities are
 * {@code @Version}-managed, so Hibernate binds the version as a second parameter.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class SoftDeletable extends BaseEntity {

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
