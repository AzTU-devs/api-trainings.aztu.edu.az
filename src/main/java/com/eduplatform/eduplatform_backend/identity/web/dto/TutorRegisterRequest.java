package com.eduplatform.eduplatform_backend.identity.web.dto;

import com.eduplatform.eduplatform_backend.tutor.web.dto.HttpUrl;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Public self-registration for tutors. Creates the user account AND a PENDING tutor
 * profile + approval request in one step. The account holds the USER role until an
 * admin approves the application, at which point the TUTOR role is granted.
 *
 * <p>The areas of expertise are {@code categoryIds}, picked from the catalogue, and
 * {@code customExpertise}, typed in by the applicant; either may be empty or left out, but not both
 * (400 EXPERTISE_REQUIRED). The custom labels are normalised and bounded by CustomExpertise, and
 * travel on the pending sign-up row to the profile the verify step creates.
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
        @Pattern(regexp = ValidationPatterns.LOCALE_OR_BLANK, message = ValidationPatterns.LOCALE_MESSAGE)
        String locale,
        // --- tutor profile ---
        @Size(max = 160) String headline,
        @Size(max = 5000) String bio,
        // The same limits as the profile edit (UpdateTutorProfileRequest). Without them sign-up
        // stored javascript: and data: links that the public expert page then rendered as hrefs,
        // and a 500-year career that no later profile save could get past.
        @Min(0) @Max(80) Short yearsExperience,
        @Size(max = 255) @HttpUrl String websiteUrl,
        @Size(max = 255) @HttpUrl String linkedinUrl,
        Set<@NotNull UUID> categoryIds,
        List<String> customExpertise
) {}
