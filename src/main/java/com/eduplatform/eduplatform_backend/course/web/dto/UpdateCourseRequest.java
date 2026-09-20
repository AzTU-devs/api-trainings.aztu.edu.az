package com.eduplatform.eduplatform_backend.course.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.CourseLevel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * Partial update — null fields are ignored by the service, so nothing here can be set back to
 * null. {@code thumbnailMediaId} and {@code trailerMediaId} follow the same rule: null keeps the
 * current media, and a new id is checked exactly as on create.
 */
public record UpdateCourseRequest(
        @Size(max = 160) String title,
        @Size(max = 255) String subtitle,
        @Size(max = 20000) String description,
        @Size(max = 5000) String requirements,
        @Size(max = 5000) String learningOutcomes,
        @Size(max = 20000) String syllabus,
        UUID thumbnailMediaId,
        UUID trailerMediaId,
        CourseLevel level,
        @Size(max = 8) String language,
        Boolean free,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @Size(min = 3, max = 3) String currency,
        Set<UUID> categoryIds,
        Set<UUID> tagIds,
        OnlineDetailsDto onlineDetails,
        OfflineDetailsDto offlineDetails
) {}
