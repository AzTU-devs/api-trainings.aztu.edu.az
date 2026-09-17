package com.eduplatform.eduplatform_backend.video.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Declared details of a video about to be uploaded. All three are client claims: the service
 * checks them against the upload policy here so the client fails fast, and re-checks the real
 * bytes when they arrive.
 */
public record VideoInitRequest(
        @NotBlank @Size(max = 255) String filename,
        @PositiveOrZero long sizeBytes,
        @Size(max = 120) String mime
) {}
