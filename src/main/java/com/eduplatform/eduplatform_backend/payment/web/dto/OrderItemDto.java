package com.eduplatform.eduplatform_backend.payment.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.OrderItemType;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemDto(
        UUID id,
        OrderItemType itemType,
        UUID courseId,
        UUID roomBookingId,
        String description,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        String currency
) {}
