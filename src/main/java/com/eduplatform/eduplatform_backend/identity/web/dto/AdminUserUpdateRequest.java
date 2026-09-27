package com.eduplatform.eduplatform_backend.identity.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Partial update of a user from the admin dashboard. Every field is optional;
 * only non-null fields are applied. {@code roles}, when present and non-empty,
 * replaces the user's full role set. A new {@code password} follows the self-signup rule, and
 * a blank one leaves the password unchanged.
 *
 * <p>{@code currentPassword} is read only when administrators change their own email: it is the
 * caller's password, required for that change (see UserAdminService.update). Optional and
 * ignored otherwise, so every existing client sends a valid request.
 */
public record AdminUserUpdateRequest(
        @Email @Size(max = 255) String email,
        @Size(max = 161) String fullName,
        @Pattern(regexp = ValidationPatterns.PHONE_OR_BLANK, message = ValidationPatterns.PHONE_MESSAGE)
        String phone,
        Set<@NotNull RoleCode> roles,
        @Pattern(regexp = ValidationPatterns.PASSWORD_OR_BLANK, message = ValidationPatterns.PASSWORD_MESSAGE)
        String password,
        String status,
        @Size(max = 100) String currentPassword
) {
    /** The shape every caller used before {@code currentPassword} existed. */
    public AdminUserUpdateRequest(String email, String fullName, String phone, Set<RoleCode> roles,
                                  String password, String status) {
        this(email, fullName, phone, roles, password, status, null);
    }
}
