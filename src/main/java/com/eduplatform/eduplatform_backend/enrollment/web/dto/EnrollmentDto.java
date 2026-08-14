package com.eduplatform.eduplatform_backend.enrollment.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.EnrollmentSource;
import com.eduplatform.eduplatform_backend.common.enums.EnrollmentStatus;

import java.time.Instant;
import java.util.UUID;

public record EnrollmentDto(
        UUID id,
        UUID userId,
        UUID courseId,
        String courseTitle,
        EnrollmentStatus status,
        EnrollmentSource source,
        Instant enrolledAt,
        Instant completedAt,
        short progressPercent,
        Instant lastAccessedAt
) {}
