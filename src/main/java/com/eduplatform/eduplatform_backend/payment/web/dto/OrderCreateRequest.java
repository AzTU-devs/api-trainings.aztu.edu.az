package com.eduplatform.eduplatform_backend.payment.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.OrderItemType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record OrderCreateRequest(
        @NotEmpty @Valid List<Line> items,
        @Size(max = 3) String currency
) {
    public record Line(
            @NotNull OrderItemType itemType,
            UUID courseId,
            UUID roomBookingId
    ) {}
}
