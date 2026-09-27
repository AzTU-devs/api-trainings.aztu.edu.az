package com.eduplatform.eduplatform_backend.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A signed-in user changing their own password. The new one follows the sign-up rule.
 * {@code refreshToken} is optional: the session to keep signed in when the client holds its
 * refresh token itself (the public site's BFF); the dashboard's comes from its cookie.
 */
public record ChangePasswordRequest(
        @NotBlank @Size(max = 100) String currentPassword,
        @NotBlank
        @Pattern(regexp = ValidationPatterns.PASSWORD_OR_BLANK, message = ValidationPatterns.PASSWORD_MESSAGE)
        String newPassword,
        String refreshToken
) {}
