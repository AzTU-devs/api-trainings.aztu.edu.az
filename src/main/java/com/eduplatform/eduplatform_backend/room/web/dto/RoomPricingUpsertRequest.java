package com.eduplatform.eduplatform_backend.room.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record RoomPricingUpsertRequest(
        @NotBlank @Size(max = 80) String name,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal hourlyRate,
        @NotBlank @Size(min = 3, max = 3) String currency,
        Short dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        LocalDate validFrom,
        LocalDate validTo,
        Integer priority
) {}
