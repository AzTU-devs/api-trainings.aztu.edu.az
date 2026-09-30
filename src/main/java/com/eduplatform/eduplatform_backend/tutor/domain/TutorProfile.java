package com.eduplatform.eduplatform_backend.tutor.domain;

import com.eduplatform.eduplatform_backend.catalog.domain.Category;
import com.eduplatform.eduplatform_backend.common.domain.SoftDeletable;
import com.eduplatform.eduplatform_backend.common.enums.TutorApprovalStatus;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "tutor_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@SQLDelete(sql = "UPDATE tutor_profiles SET deleted_at = now() WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL")
public class TutorProfile extends SoftDeletable {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /**
     * The expert's name as courses show it, read straight from the users row. Courses name their
     * experts in every list and detail, and going through {@link #user} for it meant loading the
     * account, which throws for an account deleted before deleting retired the profile with it
     * (V21): one such expert turned the admin view of their courses into a 404. The subselect
     * sees the row whether or not it is deleted, so such a course keeps its expert's name and can
     * still be opened and reassigned. Never written.
     */
    @Formula("(select concat(u.first_name, ' ', u.last_name) from users u where u.id = user_id)")
    private String displayName;

    @Column(name = "headline", length = 160)
    private String headline;

    @Column(name = "bio", columnDefinition = "text")
    private String bio;

    @Column(name = "years_experience")
    private Short yearsExperience;

    @Column(name = "website_url", length = 255)
    private String websiteUrl;

    @Column(name = "linkedin_url", length = 255)
    private String linkedinUrl;

    /**
     * The expert's portrait, modelled like a course's thumbnail. Lazy because nothing reads more
     * than its id, which a proxy answers without a select.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "avatar_media_id")
    private MediaFile avatar;

    @Column(name = "academic_title", length = 120)
    private String academicTitle;

    @Column(name = "department", length = 160)
    private String department;

    /** Free text, one qualification per line. */
    @Column(name = "education", columnDefinition = "text")
    private String education;

    /** Free text, one certification per line. */
    @Column(name = "certifications", columnDefinition = "text")
    private String certifications;

    @Column(name = "languages", length = 255)
    private String languages;

    @Column(name = "google_scholar_url", length = 255)
    private String googleScholarUrl;

    @Column(name = "research_gate_url", length = 255)
    private String researchGateUrl;

    /** The bare iD, 0000-0000-0000-000X, not the orcid.org URL. */
    @Column(name = "orcid", length = 255)
    private String orcid;

    @Column(name = "github_url", length = 255)
    private String githubUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    @Builder.Default
    private TutorApprovalStatus approvalStatus = TutorApprovalStatus.PENDING;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by", columnDefinition = "uuid")
    private UUID approvedBy;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "rating_avg", nullable = false, precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal ratingAvg = BigDecimal.ZERO;

    @Column(name = "rating_count", nullable = false)
    @Builder.Default
    private int ratingCount = 0;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "tutor_expertises",
            joinColumns = @JoinColumn(name = "tutor_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id"))
    @BatchSize(size = 50)
    @Builder.Default
    private Set<Category> expertises = new HashSet<>();

    /**
     * Areas of expertise the expert typed in themselves, next to the {@link #expertises} they
     * picked from the catalogue: plain-text labels, normalised by CustomExpertise, in the order
     * given. A JSONB array rather than a table, since they are only ever shown with the profile
     * and replaced as a whole. Always replaced with a new list, never changed in place, so
     * Hibernate sees the change.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_expertise", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> customExpertise = new ArrayList<>();
}
