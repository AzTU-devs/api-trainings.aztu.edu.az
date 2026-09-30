package com.eduplatform.eduplatform_backend.course.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create or replace a module. The description may be the dashboard editor's HTML and is sanitised
 * on write (see RichTextSanitizer); its limit went from 2000 to 10000 to leave room for the markup.
 */
public record ModuleUpsertRequest(
        @NotBlank @Size(max = 160) String title,
        @Size(max = 10000) String description,
        @Min(0) int orderIndex
) {}
