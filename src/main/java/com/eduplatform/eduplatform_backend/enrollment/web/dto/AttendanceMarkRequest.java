package com.eduplatform.eduplatform_backend.enrollment.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.AttendanceStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AttendanceMarkRequest(
        @NotNull UUID enrollmentId,
        @NotNull AttendanceStatus status,
        @Size(max = 1000) String note
) {}
