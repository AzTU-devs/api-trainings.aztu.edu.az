package com.eduplatform.eduplatform_backend.identity.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.AuthProvider;

import java.time.Instant;
import java.util.UUID;

public record UserIdentityDto(
        UUID id,
        AuthProvider provider,
        String emailAtProvider,
        boolean emailVerified,
        String displayName,
        String avatarUrl,
        boolean privateEmail,
        Instant linkedAt,
        Instant lastLoginAt
) {}
