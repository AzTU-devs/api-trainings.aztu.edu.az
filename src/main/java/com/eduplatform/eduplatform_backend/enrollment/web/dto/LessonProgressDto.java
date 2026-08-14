package com.eduplatform.eduplatform_backend.enrollment.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.LessonProgressStatus;

import java.time.Instant;
import java.util.UUID;

public record LessonProgressDto(
        UUID lessonId,
        LessonProgressStatus status,
        int positionSec,
        Instant completedAt
) {}
