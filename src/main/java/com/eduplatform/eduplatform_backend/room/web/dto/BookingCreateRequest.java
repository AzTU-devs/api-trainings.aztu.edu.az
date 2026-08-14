package com.eduplatform.eduplatform_backend.room.web.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record BookingCreateRequest(
        @NotNull UUID roomId,
        UUID offlineCourseId,
        @NotNull @Future Instant startsAt,
        @NotNull @Future Instant endsAt,
        @Size(max = 255) String recurrenceRule
) {}
