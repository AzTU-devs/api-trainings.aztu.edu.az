package com.eduplatform.eduplatform_backend.course.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.LessonContentType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record LessonUpsertRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 5000) String description,
        @NotNull LessonContentType contentType,
        UUID videoMediaId,
        @Size(max = 512) String videoUrl,
        @Min(0) int durationSeconds,
        @Min(0) int orderIndex,
        boolean preview
) {}
