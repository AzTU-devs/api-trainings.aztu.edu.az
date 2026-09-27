package com.eduplatform.eduplatform_backend.identity.web.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Self-service profile update for the currently authenticated user. Every field is optional:
 * null leaves it unchanged. A name that is sent must not be blank (the account has to keep one);
 * a blank phone clears it.
 */
public record ProfileUpdateRequest(
        @Size(max = 80) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String firstName,
        @Size(max = 80) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String lastName,
        @Pattern(regexp = ValidationPatterns.PHONE_OR_BLANK, message = ValidationPatterns.PHONE_MESSAGE)
        String phone,
        @Pattern(regexp = ValidationPatterns.LOCALE_OR_BLANK, message = ValidationPatterns.LOCALE_MESSAGE)
        String locale
) {}
