package com.eduplatform.eduplatform_backend.identity.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.UserStatus;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record UserDto(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String phone,
        String locale,
        UserStatus status,
        boolean emailVerified,
        Instant lastLoginAt,
        Set<String> roles,
        Set<String> permissions
) {}
