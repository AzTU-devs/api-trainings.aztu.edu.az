package com.eduplatform.eduplatform_backend.enrollment.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.EnrollmentSource;
import com.eduplatform.eduplatform_backend.common.enums.EnrollmentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of a course's participant roster, as the dashboard's participants screen renders it:
 * the person, how they got their place and how far they have come.
 */
public record CourseParticipantDto(
        UUID enrollmentId,
        UUID userId,
        String fullName,
        String email,
        String avatarUrl,
        EnrollmentStatus status,
        EnrollmentSource source,
        Instant enrolledAt,
        Instant completedAt,
        short progressPercent,
        Instant lastAccessedAt
) {}
