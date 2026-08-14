package com.eduplatform.eduplatform_backend.tutor.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.TutorApprovalStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

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
        TutorApprovalStatus approvalStatus,
        Instant approvedAt,
        BigDecimal ratingAvg,
        int ratingCount,
        Set<UUID> expertiseCategoryIds
) {}
