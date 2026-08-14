package com.eduplatform.eduplatform_backend.identity.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

/**
 * Create a staff/user account from the admin dashboard.
 * {@code password} is optional — when blank the account is created without a
 * password (it cannot log in until one is set / a reset is issued).
 */
public record AdminUserCreateRequest(
        @NotBlank @Email String email,
        @NotBlank String fullName,
        String phone,
        @NotEmpty(message = "Assign at least one role") Set<RoleCode> roles,
        String password
) {}
