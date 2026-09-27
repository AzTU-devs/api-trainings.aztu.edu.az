package com.eduplatform.eduplatform_backend.tutor.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.TutorApprovalStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * An approved expert's profile as anybody may read it on the public site.
 *
 * <p>The same fields as {@link TutorProfileDto} minus the ones that identify the account rather
 * than describe the expert: {@code userId} is the id the admin user endpoints and the audit log
 * are keyed on, and {@code avatarMediaId} is only needed by the dashboard's editor, while the
 * page itself loads {@code avatarUrl}. {@code approvalStatus} stays, because the site's expert
 * page and directory check it.
 */
public record TutorPublicProfileDto(
        UUID id,
        String firstName,
        String lastName,
        String headline,
        String bio,
        Short yearsExperience,
        String websiteUrl,
        String linkedinUrl,
        String avatarUrl,
        String academicTitle,
        String department,
        String education,
        String certifications,
        String languages,
        String googleScholarUrl,
        String researchGateUrl,
        String orcid,
        String githubUrl,
        TutorApprovalStatus approvalStatus,
        Instant approvedAt,
        BigDecimal ratingAvg,
        int ratingCount,
        Set<UUID> expertiseCategoryIds
) {}
