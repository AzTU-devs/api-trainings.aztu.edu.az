package com.eduplatform.eduplatform_backend.payment.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderDto(
        UUID id,
        UUID userId,
        String orderNumber,
        OrderStatus status,
        BigDecimal subtotal,
        BigDecimal tax,
        BigDecimal discount,
        BigDecimal total,
        String currency,
        Instant placedAt,
        Instant paidAt,
        List<OrderItemDto> items
) {}
