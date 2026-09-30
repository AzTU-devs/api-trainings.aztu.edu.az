package com.eduplatform.eduplatform_backend.tutor.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * An existing account applying to become an expert.
 *
 * <p>The areas of expertise are {@code categoryIds}, picked from the catalogue, and
 * {@code customExpertise}, typed in by the applicant; either may be empty or left out, but not both
 * (400 EXPERTISE_REQUIRED). The custom labels are normalised and bounded by CustomExpertise.
 */
public record TutorApplyRequest(
        @Size(max = 160) String headline,
        @Size(max = 5000) String bio,
        @Min(0) @Max(80) Short yearsExperience,
        @Size(max = 255) @HttpUrl String websiteUrl,
        @Size(max = 255) @HttpUrl String linkedinUrl,
        Set<@NotNull UUID> categoryIds,
        List<String> customExpertise
) {}
