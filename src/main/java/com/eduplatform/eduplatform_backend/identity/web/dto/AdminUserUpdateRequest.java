package com.eduplatform.eduplatform_backend.identity.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import jakarta.validation.constraints.Email;

import java.util.Set;

/**
 * Partial update of a user from the admin dashboard. Every field is optional;
 * only non-null fields are applied. {@code roles}, when present and non-empty,
 * replaces the user's full role set.
 */
public record AdminUserUpdateRequest(
        @Email String email,
        String fullName,
        String phone,
        Set<RoleCode> roles,
        String password,
        String status
) {}
