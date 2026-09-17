package com.eduplatform.eduplatform_backend.course.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.CourseLevel;
import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
        /** Full teaching roster; tutorId above is the one authorised to edit. */
        List<CourseTutorDto> tutors,
        Instant publishedAt,
        /**
         * Course length in seconds, normalised across both course types: online video
         * seconds, or offline contact hours converted to seconds. Null when the course
         * has no type-specific detail row yet.
         */
        Integer totalDurationSec,
        /** Relative URL of the public thumbnail stream, or null when there is no thumbnail. */
        String thumbnailUrl
) {}
