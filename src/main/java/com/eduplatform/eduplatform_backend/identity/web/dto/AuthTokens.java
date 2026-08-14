package com.eduplatform.eduplatform_backend.identity.web.dto;

import java.time.Instant;

public record AuthTokens(
        String accessToken,
        String refreshToken,
        String tokenType,
        Instant accessExpiresAt,
        Instant refreshExpiresAt,
        UserDto user
) {}
