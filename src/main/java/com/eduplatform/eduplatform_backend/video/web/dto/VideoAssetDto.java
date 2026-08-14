package com.eduplatform.eduplatform_backend.video.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/** Mirrors the dashboard {@code VideoAsset} contract. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VideoAssetDto(
        UUID id,
        String title,
        String filename,
        String url,
        String thumbnailUrl,
        int durationSeconds,
        long sizeBytes,
        String status,
        UUID courseId,
        UUID lessonId,
        Instant uploadedAt
) {}
