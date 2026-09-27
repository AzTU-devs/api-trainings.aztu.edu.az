package com.eduplatform.eduplatform_backend.course.web.dto;

import java.util.List;
import java.util.UUID;

public record ModuleDto(
        UUID id,
        String title,
        String description,
        int orderIndex,
        List<LessonDto> lessons
) {

    /** See CourseDto.withLessonContentHidden. */
    public ModuleDto withLessonContentHidden() {
        return new ModuleDto(id, title, description, orderIndex,
                lessons == null ? null : lessons.stream().map(LessonDto::withContentHiddenUnlessPreview).toList());
    }
}
