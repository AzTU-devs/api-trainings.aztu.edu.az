package com.eduplatform.eduplatform_backend.course.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.CourseLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * Partial update — null fields are ignored by the service, so a plain field can never be set back
 * to null. {@code thumbnailMediaId} and {@code trailerMediaId} follow the same rule: null keeps the
 * current media, and a new id is checked exactly as on create. Removing the cover or the trailer
 * is what {@code clearThumbnail} and {@code clearTrailer} are for: a record cannot tell an absent
 * id from an explicit null, so "Remove" used to save as "keep" and the dashboard reported a
 * removal that never happened.
 *
 * <p>{@code offlineDetails} and {@code onlineDetails} are merged field by field too: a property
 * left null keeps its value.
 *
 * <p>{@code version} is the course's version as last read. When sent, a mismatch is refused with
 * 409 STALE_RESOURCE, so two people editing the same course cannot silently overwrite each other;
 * omitted, the edit is applied without that check.
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
        Set<@NotNull UUID> categoryIds,
        Set<@NotNull UUID> tagIds,
        OnlineDetailsDto onlineDetails,
        @Valid OfflineDetailsDto offlineDetails,
        /** True removes the cover image; thumbnailMediaId is then ignored. */
        Boolean clearThumbnail,
        /** True removes the trailer; trailerMediaId is then ignored. */
        Boolean clearTrailer,
        Long version
) {}
