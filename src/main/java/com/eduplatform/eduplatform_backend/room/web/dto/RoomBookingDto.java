package com.eduplatform.eduplatform_backend.room.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RoomBookingDto(
        UUID id,
        UUID roomId,
        String roomName,
        UUID offlineCourseId,
        UUID tutorId,
        Instant startsAt,
        Instant endsAt,
        String recurrenceRule,
        BookingStatus status,
        BigDecimal totalFee,
        String currency,
        Instant createdAt
) {}
