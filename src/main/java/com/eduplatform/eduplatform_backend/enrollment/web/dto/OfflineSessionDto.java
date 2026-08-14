package com.eduplatform.eduplatform_backend.enrollment.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.SessionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record OfflineSessionDto(
        UUID id,
        UUID offlineCourseId,
        LocalDate sessionDate,
        Instant startsAt,
        Instant endsAt,
        String topic,
        SessionStatus status
) {}
