package com.eduplatform.eduplatform_backend.course.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One syllabus entry, in requests and responses alike.
 *
 * <p>The title is plain text and is stored trimmed. The description is the dashboard editor's HTML
 * (or plain text) and is sanitised on write to the rich-text allowlist, like the course's own
 * description (see RichTextSanitizer); a missing one is stored, and returned, as "".
 */
public record SyllabusItemDto(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 20000) String description
) {}
