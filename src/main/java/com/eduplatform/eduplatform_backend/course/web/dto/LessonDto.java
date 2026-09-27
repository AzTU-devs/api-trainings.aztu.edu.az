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
) {

    /**
     * A preview lesson unchanged; any other lesson reduced to what the course outline shows. The
     * description is the lesson's text, videoUrl a video or the live session's meeting link, and
     * videoMediaId its file — all of it what a participant is given, not what a visitor browsing
     * the catalogue should be able to read off the public API.
     */
    public LessonDto withContentHiddenUnlessPreview() {
        return preview ? this
                : new LessonDto(id, title, null, contentType, null, null, durationSeconds, orderIndex, false);
    }
}
