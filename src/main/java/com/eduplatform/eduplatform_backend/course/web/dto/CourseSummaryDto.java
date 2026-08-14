package com.eduplatform.eduplatform_backend.course.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.CourseLevel;
import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Lightweight course card for catalog browsing. */
public record CourseSummaryDto(
        UUID id,
        String slug,
        String title,
        String subtitle,
        CourseType courseType,
        CourseLevel level,
        String language,
        boolean free,
        BigDecimal price,
        String currency,
        CourseStatus status,
        BigDecimal ratingAvg,
        int ratingCount,
        int enrolledCount,
        UUID tutorId,
        String tutorDisplayName,
        Instant publishedAt
) {}
