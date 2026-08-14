package com.eduplatform.eduplatform_backend.course.web.dto;

import java.util.List;
import java.util.UUID;

public record ModuleDto(
        UUID id,
        String title,
        String description,
        int orderIndex,
        List<LessonDto> lessons
) {}
