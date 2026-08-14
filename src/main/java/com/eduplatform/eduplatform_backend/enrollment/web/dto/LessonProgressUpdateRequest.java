package com.eduplatform.eduplatform_backend.enrollment.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.LessonProgressStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record LessonProgressUpdateRequest(
        @NotNull LessonProgressStatus status,
        @Min(0) int positionSec
) {}
