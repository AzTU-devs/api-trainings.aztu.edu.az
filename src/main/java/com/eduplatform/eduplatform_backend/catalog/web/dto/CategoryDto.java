package com.eduplatform.eduplatform_backend.catalog.web.dto;

import java.util.UUID;

public record CategoryDto(
        UUID id,
        UUID parentId,
        String slug,
        String name,
        String description,
        String iconUrl,
        int sortOrder,
        boolean active
) {}
