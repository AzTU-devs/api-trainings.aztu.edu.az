package com.eduplatform.eduplatform_backend.room.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RoomBookingDto(
        UUID id,
        UUID roomId,
        String roomName,
        /** The room's status now (AVAILABLE, MAINTENANCE, RESERVED, RETIRED); null once deleted. */
        String roomStatus,
        UUID offlineCourseId,
        String offlineCourseTitle,
        UUID tutorId,
        /** Who asked for the room, so a moderator does not have to look a profile id up. */
        String tutorName,
        String tutorEmail,
        Instant startsAt,
        Instant endsAt,
        String recurrenceRule,
        BookingStatus status,
        BigDecimal totalFee,
        String currency,
        Instant createdAt
) {}
