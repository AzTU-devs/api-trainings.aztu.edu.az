package com.eduplatform.eduplatform_backend.identity.domain;

import com.eduplatform.eduplatform_backend.common.domain.SoftDeletable;
import com.eduplatform.eduplatform_backend.common.enums.AuthProvider;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(
        name = "user_identities",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_identity_provider_subject", columnNames = {"provider", "provider_user_id"}),
                @UniqueConstraint(name = "uq_identity_user_provider", columnNames = {"user_id", "provider"})
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@SQLDelete(sql = "UPDATE user_identities SET deleted_at = now() WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL")
public class UserIdentity extends SoftDeletable {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private AuthProvider provider;

    /** Provider-side stable id (e.g. Google `sub`, Apple `sub`, Facebook user id). */
    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(name = "email_at_provider", length = 255)
    private String emailAtProvider;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @Column(name = "display_name", length = 160)
    private String displayName;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    /** Apple may return a per-app private relay address. */
    @Column(name = "is_private_email", nullable = false)
    @Builder.Default
    private boolean privateEmail = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_profile", columnDefinition = "jsonb")
    private Map<String, Object> rawProfile;

    /** Encrypted via the app-level crypto helper before persistence. */
    @Column(name = "provider_refresh_token_encrypted", columnDefinition = "bytea")
    private byte[] providerRefreshTokenEncrypted;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @PrePersist
    void onCreate() {
        if (linkedAt == null) linkedAt = Instant.now();
    }
}
