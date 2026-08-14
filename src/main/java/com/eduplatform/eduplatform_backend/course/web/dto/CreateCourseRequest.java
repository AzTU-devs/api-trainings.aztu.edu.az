package com.eduplatform.eduplatform_backend.course.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.CourseLevel;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record CreateCourseRequest(
        @NotBlank @Size(max = 160) @Pattern(regexp = "^[a-z0-9-]+$", message = "slug must be lowercase kebab-case")
        String slug,
        @NotBlank @Size(max = 160) String title,
        @Size(max = 255) String subtitle,
        @Size(max = 20000) String description,
        @Size(max = 5000) String requirements,
        @Size(max = 5000) String learningOutcomes,
        @Size(max = 20000) String syllabus,
        UUID thumbnailMediaId,
        UUID trailerMediaId,
        @NotNull CourseType courseType,
        CourseLevel level,
        @Size(max = 8) String language,
        @NotNull Boolean free,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @NotNull @Size(min = 3, max = 3) String currency,
        @NotNull Set<UUID> categoryIds,
        Set<UUID> tagIds,
        OnlineDetailsDto onlineDetails,
        OfflineDetailsDto offlineDetails
) {}
