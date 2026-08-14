package com.eduplatform.eduplatform_backend.video.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record VideoInitRequest(
        @NotBlank String filename,
        @PositiveOrZero long sizeBytes,
        String mime
) {}
