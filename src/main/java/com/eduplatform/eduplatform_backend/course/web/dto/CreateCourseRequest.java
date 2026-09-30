package com.eduplatform.eduplatform_backend.course.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.CourseLevel;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A new course. {@code description}, {@code requirements}, {@code learningOutcomes} and each
 * syllabus item's description may be the dashboard editor's HTML; it is sanitised on write (see
 * RichTextSanitizer). Their limits leave room for the markup, which is why requirements and
 * learning outcomes went from 5000 to 20000.
 */
public record CreateCourseRequest(
        @NotBlank @Size(max = 160) @Pattern(regexp = "^[a-z0-9-]+$", message = "slug must be lowercase kebab-case")
        String slug,
        @NotBlank @Size(max = 160) String title,
        @Size(max = 255) String subtitle,
        @Size(max = 20000) String description,
        @Size(max = 20000) String requirements,
        @Size(max = 20000) String learningOutcomes,
        @Size(max = 20000) String syllabus,
        /** In display order; null or absent is no syllabus. */
        @Size(max = 100) List<@NotNull @Valid SyllabusItemDto> syllabusItems,
        UUID thumbnailMediaId,
        UUID trailerMediaId,
        @NotNull CourseType courseType,
        CourseLevel level,
        @Size(max = 8) String language,
        @NotNull Boolean free,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @NotNull @Size(min = 3, max = 3) String currency,
        // Element constraints: a null in either list used to reach findById and come back 500.
        @NotNull Set<@NotNull UUID> categoryIds,
        Set<@NotNull UUID> tagIds,
        OnlineDetailsDto onlineDetails,
        @Valid OfflineDetailsDto offlineDetails
) {}
