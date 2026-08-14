package com.eduplatform.eduplatform_backend.course.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.CourseLevel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/** Partial update — null fields are ignored by the service. */
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
