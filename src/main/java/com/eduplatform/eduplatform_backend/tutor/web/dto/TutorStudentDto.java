package com.eduplatform.eduplatform_backend.tutor.web.dto;

import java.time.Instant;
import java.util.UUID;

/** A student enrolled in the current tutor's courses, aggregated across all of them. */
public record TutorStudentDto(
        UUID id,
        String fullName,
        String email,
        String avatarUrl,
        long activeEnrollments,
        long totalEnrollments,
        double averageProgressPct,
        Instant lastActivityAt
) {}
