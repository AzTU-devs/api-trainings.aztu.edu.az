package com.eduplatform.eduplatform_backend.catalog.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TagUpsertRequest(
        @NotBlank @Size(max = 60) @Pattern(regexp = "^[a-z0-9-]+$", message = "slug must be lowercase kebab-case")
        String slug,
        @NotBlank @Size(max = 60) String name
) {}
