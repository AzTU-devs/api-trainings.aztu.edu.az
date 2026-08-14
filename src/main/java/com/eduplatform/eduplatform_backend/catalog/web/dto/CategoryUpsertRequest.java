package com.eduplatform.eduplatform_backend.catalog.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CategoryUpsertRequest(
        @NotBlank @Size(max = 120) @Pattern(regexp = "^[a-z0-9-]+$", message = "slug must be lowercase kebab-case")
        String slug,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 2000) String description,
        @Size(max = 255) String iconUrl,
        UUID parentId,
        int sortOrder,
        Boolean active
) {}
