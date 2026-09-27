package com.eduplatform.eduplatform_backend.course.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The in-person part of an OFFLINE course. On create the dates and the student limit are
 * required; on a partial update any property left null keeps its value. {@code enrolledCount}
 * is output only: the seats taken, which the enrolment service keeps.
 *
 * <p>The hours are bounded by what they mean — a week has 168 — and by their columns
 * (NUMERIC(6,1) for the total). Negative hours used to be stored and shown on the public course
 * page, and a value past a column's precision failed as a generic "cannot be stored" rather than
 * as the field that was wrong. The table enforces the same rule (V21).
 */
public record OfflineDetailsDto(
        LocalDate startDate,
        LocalDate endDate,
        @DecimalMin(value = "0", message = "must not be negative")
        @DecimalMax(value = "168", message = "must be at most 168 hours a week")
        BigDecimal weeklyHours,
        @DecimalMin(value = "0", message = "must not be negative")
        @DecimalMax(value = "99999.9", message = "must be at most 99999.9 hours")
        BigDecimal totalHours,
        Integer studentLimit,
        int enrolledCount,
        String city,
        String addressLine
) {}
