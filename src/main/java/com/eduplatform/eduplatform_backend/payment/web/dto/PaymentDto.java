package com.eduplatform.eduplatform_backend.payment.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.PaymentProvider;
import com.eduplatform.eduplatform_backend.common.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentDto(
        UUID id,
        UUID orderId,
        PaymentProvider provider,
        String providerPaymentId,
        String providerIntentId,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String method,
        String errorCode,
        String errorMessage,
        Instant createdAt
) {}
