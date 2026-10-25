package com.eduplatform.eduplatform_backend.room.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.BookingStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record RoomBookingSeriesDto(
        UUID id,
        UUID roomId,
        String roomName,
        UUID offlineCourseId,
        String offlineCourseTitle,
        UUID tutorId,
        String tutorName,
        String tutorEmail,
        LocalDate startDate,
        LocalDate endDate,
        /** Monday first. */
        List<DayOfWeek> daysOfWeek,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        /** The zone the dates and times are in. */
        String timezone,
        BookingStatus status,
        int occurrenceCount,
        /** Occurrences not yet started that are still pending or approved. */
        int upcomingCount,
        /** The fee quoted at request time: the sum over every occurrence. */
        BigDecimal totalFee,
        String currency,
        String note,
        String decisionNote,
        Instant decidedAt,
        Instant createdAt,
        /** In date order. Empty in the lists, to keep them light; complete in the detail. */
        List<Occurrence> occurrences
) {

    public record Occurrence(UUID bookingId, Instant startsAt, Instant endsAt, BookingStatus status) {}
}
