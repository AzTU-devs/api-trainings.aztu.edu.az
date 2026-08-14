package com.eduplatform.eduplatform_backend.course.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ModuleUpsertRequest(
        @NotBlank @Size(max = 160) String title,
        @Size(max = 2000) String description,
        @Min(0) int orderIndex
) {}
