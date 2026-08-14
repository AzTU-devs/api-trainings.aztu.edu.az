package com.eduplatform.eduplatform_backend.room.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.RoomStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RoomUpsertRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 40)  String roomNumber,
        @Size(max = 120) String building,
        @Min(1) int capacity,
        @Size(max = 2000) String description,
        RoomStatus status,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal hourlyRate,
        @NotNull @Size(min = 3, max = 3) String currency,
        @NotNull(message = "At least 2 images are required")
        @Size(min = 2, message = "At least 2 images are required")
        List<UUID> imageMediaIds
) {}
