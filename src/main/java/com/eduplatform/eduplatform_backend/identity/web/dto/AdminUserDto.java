package com.eduplatform.eduplatform_backend.identity.web.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Admin-facing user view. Mirrors the dashboard's {@code AdminUser} type:
 * the name is presented as a single {@code fullName} and {@code status} uses
 * the dashboard vocabulary (ACTIVE / DISABLED / PENDING).
 */
public record AdminUserDto(
        UUID id,
        String email,
        String fullName,
        String phone,
        Set<String> roles,
        String status,
        Instant createdAt,
        Instant lastLoginAt
) {}
