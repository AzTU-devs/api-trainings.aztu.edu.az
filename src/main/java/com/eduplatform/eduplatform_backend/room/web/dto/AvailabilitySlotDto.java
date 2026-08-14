package com.eduplatform.eduplatform_backend.room.web.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record AvailabilitySlotDto(
        UUID id,
        short dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        LocalDate validFrom,
        LocalDate validTo
) {}
