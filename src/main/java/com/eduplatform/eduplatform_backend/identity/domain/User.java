package com.eduplatform.eduplatform_backend.identity.domain;

import com.eduplatform.eduplatform_backend.common.domain.SoftDeletable;
import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@SQLDelete(sql = "UPDATE users SET deleted_at = now() WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL")
public class User extends SoftDeletable {

    // Unique among live accounts only (uq_users_email_active, V16), so a deleted account's
    // address can register again.
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "phone", length = 32)
    private String phone;

    /** NULL for social-only accounts. */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 80)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 80)
    private String lastName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "avatar_media_id")
    private MediaFile avatar;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "failed_logins", nullable = false)
    @Builder.Default
    private short failedLogins = 0;

    /**
     * Set when too many wrong passwords arrive in a row; password sign-in is refused until then.
     * A lock that passes by itself, not a status: anyone can type five wrong passwords for
     * someone else's address, so a lock that needed an administrator to lift let any visitor
     * shut any account out, every SUPER_ADMIN included.
     */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    /**
     * Carried in every access token as its {@code ver} claim. Bumped whenever an administrator
     * changes this account's roles, status or password, which makes every access token issued
     * before the change stale at once rather than when it expires. See UserSessionState.
     */
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private long tokenVersion = 0;

    @Column(name = "locale", nullable = false, length = 8)
    @Builder.Default
    private String locale = "en";

    /** Azerbaijani fin code; required only for admin accounts. */
    @Column(name = "fin_kod", length = 20)
    private String finKod;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private Set<UserRole> userRoles = new HashSet<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private Set<UserIdentity> identities = new HashSet<>();
}
