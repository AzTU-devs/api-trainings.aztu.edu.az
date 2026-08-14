package com.eduplatform.eduplatform_backend.enrollment.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.AttendanceStatus;

import java.time.Instant;
import java.util.UUID;

public record AttendanceRecordDto(
        UUID id,
        UUID sessionId,
        UUID enrollmentId,
        AttendanceStatus status,
        Instant markedAt,
        String note
) {}
