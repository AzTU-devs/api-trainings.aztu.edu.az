package com.eduplatform.eduplatform_backend.room.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.RoomStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RoomDto(
        UUID id,
        String name,
        String roomNumber,
        String building,
        int capacity,
        String description,
        RoomStatus status,
        BigDecimal hourlyRate,
        String currency,
        List<UUID> imageMediaIds
) {}
