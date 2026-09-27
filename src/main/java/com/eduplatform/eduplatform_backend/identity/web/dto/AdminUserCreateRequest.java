package com.eduplatform.eduplatform_backend.identity.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Create a staff/user account from the admin dashboard.
 * {@code password} is optional — when blank the account is created without a
 * password (it cannot log in until one is set / a reset is issued). When given it follows the
 * self-signup rule; see {@link ValidationPatterns#PASSWORD_OR_BLANK}.
 */
public record AdminUserCreateRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 161) String fullName,
        @Pattern(regexp = ValidationPatterns.PHONE_OR_BLANK, message = ValidationPatterns.PHONE_MESSAGE)
        String phone,
        @NotEmpty(message = "Assign at least one role") Set<@NotNull RoleCode> roles,
        @Pattern(regexp = ValidationPatterns.PASSWORD_OR_BLANK, message = ValidationPatterns.PASSWORD_MESSAGE)
        String password
) {}
