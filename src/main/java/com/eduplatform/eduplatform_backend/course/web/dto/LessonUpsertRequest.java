package com.eduplatform.eduplatform_backend.course.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.LessonContentType;
import com.eduplatform.eduplatform_backend.tutor.web.dto.HttpUrl;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Create or fully replace a lesson — on update a null field clears it, unlike the course's
 * partial update.
 *
 * <p>{@code videoMediaId} is the lesson's one file whatever its content type, not only a video:
 * the document of a PDF lesson, an optional attachment on the other types. Which kinds it
 * accepts follows {@code contentType}; see {@code CourseMediaValidator.MediaField#lessonMaterial}.
 *
 * <p>{@code videoUrl} (a meeting or video link) must be an http(s) address, as an expert's profile
 * links must: only its length was checked, so a {@code javascript:} or {@code data:} link was
 * stored, and for a preview lesson served to every visitor of the public course page.
 *
 * <p>{@code description} may be the dashboard editor's HTML and is sanitised on write (see
 * RichTextSanitizer); its limit went from 5000 to 20000 to leave room for the markup.
 */
public record LessonUpsertRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 20000) String description,
        @NotNull LessonContentType contentType,
        UUID videoMediaId,
        @Size(max = 512) @HttpUrl String videoUrl,
        @Min(0) int durationSeconds,
        @Min(0) int orderIndex,
        boolean preview
) {}
