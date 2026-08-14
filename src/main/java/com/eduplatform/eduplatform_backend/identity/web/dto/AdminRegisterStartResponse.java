package com.eduplatform.eduplatform_backend.identity.web.dto;

import java.time.Instant;

public record AdminRegisterStartResponse(
        String message,
        Instant otpExpiresAt,
        int otpLength
) {}
