package com.eduplatform.eduplatform_backend.identity.web.dto;

import jakarta.validation.constraints.*;

import java.util.Set;
import java.util.UUID;

/**
 * Public self-registration for tutors. Creates the user account AND a PENDING tutor
 * profile + approval request in one step. The account holds the USER role until an
 * admin approves the application, at which point the TUTOR role is granted.
 */
public record TutorRegisterRequest(
        // --- account ---
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank
        @Size(min = 10, max = 100, message = "Password must be 10-100 characters")
        @Pattern(regexp = ".*[A-Z].*", message = "Password must contain an uppercase letter")
        @Pattern(regexp = ".*[a-z].*", message = "Password must contain a lowercase letter")
        @Pattern(regexp = ".*\\d.*",   message = "Password must contain a digit")
        String password,
        @NotBlank @Size(max = 80) String firstName,
        @NotBlank @Size(max = 80) String lastName,
        @Size(max = 32) String phone,
        @Size(max = 8) String locale,
        // --- tutor profile ---
        @Size(max = 160) String headline,
        @Size(max = 5000) String bio,
        @Min(0) Short yearsExperience,
        @Size(max = 255) String websiteUrl,
        @Size(max = 255) String linkedinUrl,
        @NotNull @NotEmpty Set<UUID> categoryIds
) {}
