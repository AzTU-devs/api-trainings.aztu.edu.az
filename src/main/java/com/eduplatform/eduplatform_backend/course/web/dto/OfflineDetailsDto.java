package com.eduplatform.eduplatform_backend.course.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * The in-person part of an OFFLINE or a ONE_TIME course. On create the dates and the student limit
 * are required, and for ONE_TIME the two times as well; on a partial update any property left null
 * keeps its value. {@code enrolledCount} is output only: the seats taken, which the enrolment
 * service keeps.
 *
 * <p>For a ONE_TIME course the server owns three of these: {@code endDate} is always the start
 * date, {@code weeklyHours} is always null and {@code totalHours} is worked out from the times —
 * whatever the client sent for them is overwritten (see CourseService).
 *
 * <p>The times travel as ISO local times: sent as {@code "10:00"} or {@code "10:00:00"}, and
 * returned as {@code "10:00:00"}.
 *
 * <p>The hours are bounded by what they mean — a week has 168 — and by their columns
 * (NUMERIC(6,1) for the total). Negative hours used to be stored and shown on the public course
 * page, and a value past a column's precision failed as a generic "cannot be stored" rather than
 * as the field that was wrong. The table enforces the same rule (V21). City and address are held to
 * their columns' lengths for the same reason: an 81-character city reached the database and failed
 * there.
 */
public record OfflineDetailsDto(
        LocalDate startDate,
        LocalDate endDate,
        LocalTime startTime,
        LocalTime endTime,
        @DecimalMin(value = "0", message = "must not be negative")
        @DecimalMax(value = "168", message = "must be at most 168 hours a week")
        BigDecimal weeklyHours,
        @DecimalMin(value = "0", message = "must not be negative")
        @DecimalMax(value = "99999.9", message = "must be at most 99999.9 hours")
        BigDecimal totalHours,
        Integer studentLimit,
        int enrolledCount,
        @Size(max = 80) String city,
        @Size(max = 255) String addressLine
) {}
