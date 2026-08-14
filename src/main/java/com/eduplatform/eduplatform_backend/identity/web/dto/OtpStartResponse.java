package com.eduplatform.eduplatform_backend.identity.web.dto;

import java.time.Instant;

/** Returned by OTP "start" steps (admin & tutor signup). */
public record OtpStartResponse(
        String message,
        Instant otpExpiresAt,
        int otpLength
) {}
