package com.eduplatform.eduplatform_backend.review.web.dto;

import java.time.Instant;
import java.util.UUID;

public record CourseReviewDto(
        UUID id,
        UUID courseId,
        UUID userId,
        String authorName,
        short rating,
        String title,
        String body,
        boolean visible,
        Instant createdAt
) {}
