package com.eduplatform.eduplatform_backend.course.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.CourseLevel;
import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Full course detail page. */
public record CourseDto(
        UUID id,
        String slug,
        String title,
        String subtitle,
        String description,
        String requirements,
        String learningOutcomes,
        String syllabus,
        UUID thumbnailMediaId,
        UUID trailerMediaId,
        CourseType courseType,
        CourseLevel level,
        String language,
        boolean free,
        BigDecimal price,
        String currency,
        CourseStatus status,
        Instant publishedAt,
        BigDecimal ratingAvg,
        int ratingCount,
        int enrolledCount,
        UUID tutorId,
        String tutorDisplayName,
        Set<UUID> categoryIds,
        Set<UUID> tagIds,
        OnlineDetailsDto onlineDetails,
        OfflineDetailsDto offlineDetails,
        List<ModuleDto> modules
) {}
