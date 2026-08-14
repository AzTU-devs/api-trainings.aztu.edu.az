package com.eduplatform.eduplatform_backend.identity.web.dto;

import jakarta.validation.constraints.*;

public record AdminRegisterStartRequest(
        @NotBlank @Size(max = 80)  String firstName,
        @NotBlank @Size(max = 80)  String lastName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 32) @Pattern(regexp = "^\\+?[0-9 ()-]{7,32}$",
                message = "phone must be 7-32 digits with optional +, spaces, dashes, parentheses")
        String phoneNumber,
        @NotBlank
        @Pattern(regexp = "^[A-Z0-9]{7}$",
                message = "fin_kod must be exactly 7 uppercase letters/digits")
        String finKod,
        @NotBlank
        @Size(min = 10, max = 100, message = "Password must be 10-100 characters")
        @Pattern(regexp = ".*[A-Z].*", message = "Password must contain an uppercase letter")
        @Pattern(regexp = ".*[a-z].*", message = "Password must contain a lowercase letter")
        @Pattern(regexp = ".*\\d.*",   message = "Password must contain a digit")
        String password
) {}
