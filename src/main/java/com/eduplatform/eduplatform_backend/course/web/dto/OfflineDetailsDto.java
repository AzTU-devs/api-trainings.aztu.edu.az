package com.eduplatform.eduplatform_backend.course.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OfflineDetailsDto(
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal weeklyHours,
        BigDecimal totalHours,
        int studentLimit,
        int enrolledCount,
        String city,
        String addressLine
) {}
