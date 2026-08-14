package com.eduplatform.eduplatform_backend.course.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.LessonContentType;

import java.util.UUID;

public record LessonDto(
        UUID id,
        String title,
        String description,
        LessonContentType contentType,
        UUID videoMediaId,
        String videoUrl,
        int durationSeconds,
        int orderIndex,
        boolean preview
) {}
