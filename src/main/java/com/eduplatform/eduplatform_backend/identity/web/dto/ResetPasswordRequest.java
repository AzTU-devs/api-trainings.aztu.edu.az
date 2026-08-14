package com.eduplatform.eduplatform_backend.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank
        @Size(min = 10, max = 100, message = "Password must be 10-100 characters")
        @Pattern(regexp = ".*[A-Z].*", message = "Password must contain an uppercase letter")
        @Pattern(regexp = ".*[a-z].*", message = "Password must contain a lowercase letter")
        @Pattern(regexp = ".*\\d.*", message = "Password must contain a digit")
        String password
) {}
