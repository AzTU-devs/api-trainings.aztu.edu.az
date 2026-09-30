package com.eduplatform.eduplatform_backend.tutor.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.TutorApprovalStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * An expert's profile, as the dashboard reads it. The public expert page gets
 * {@link TutorPublicProfileDto}, which leaves out the account-level identifiers.
 *
 * <p>{@code avatarUrl} is the anonymous media URL, which serves the image only while the profile
 * is APPROVED; the dashboard previews a pending applicant's avatar through the authenticated
 * {@code /api/media/{avatarMediaId}/content} instead.
 */
public record TutorProfileDto(
        UUID id,
        UUID userId,
        String firstName,
        String lastName,
        String headline,
        String bio,
        Short yearsExperience,
        String websiteUrl,
        String linkedinUrl,
        UUID avatarMediaId,
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
        /**
         * Why the application was rejected, shown to the expert so they know what to change before
         * resubmitting. Never on the public profile, which is served only while APPROVED anyway.
         */
        String rejectionReason,
        BigDecimal ratingAvg,
        int ratingCount,
        Set<UUID> expertiseCategoryIds,
        /** Areas the expert typed in themselves, in their order; always a list, empty when none. */
        List<String> customExpertise,
        /** The optimistic-lock version; send it back as {@code version} on PATCH. */
        long version
) {}
