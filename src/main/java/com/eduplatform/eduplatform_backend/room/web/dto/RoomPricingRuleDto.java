package com.eduplatform.eduplatform_backend.room.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record RoomPricingRuleDto(
        UUID id,
        UUID roomId,
        String name,
        BigDecimal hourlyRate,
        String currency,
        Short dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        LocalDate validFrom,
        LocalDate validTo,
        int priority
) {}
