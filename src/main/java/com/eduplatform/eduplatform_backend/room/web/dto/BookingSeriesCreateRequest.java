package com.eduplatform.eduplatform_backend.room.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * A room for {@code startTime}–{@code endTime} on every listed weekday from {@code startDate} to
 * {@code endDate}, both inclusive, in the platform's time zone. The rules that relate the fields
 * to each other (and their 400 codes) are checked by RoomBookingSeriesService; a missing weekday
 * list is DAYS_REQUIRED there rather than a generic validation failure here.
 */
public record BookingSeriesCreateRequest(
        @NotNull UUID roomId,
        /** Optional: an in-person training the expert teaches, as on a single booking. */
        UUID offlineCourseId,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        /** java.time.DayOfWeek names; duplicates count once, so seven is the most that means anything. */
        @Size(max = 7) List<DayOfWeek> daysOfWeek,
        /** "HH:mm"; "HH:mm:ss" is accepted too, and the seconds are dropped. */
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @Size(max = 500) String note
) {}
