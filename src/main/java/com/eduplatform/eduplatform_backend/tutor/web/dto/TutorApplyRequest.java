package com.eduplatform.eduplatform_backend.tutor.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record TutorApplyRequest(
        @Size(max = 160) String headline,
        @Size(max = 5000) String bio,
        @Min(0) @Max(80) Short yearsExperience,
        @Size(max = 255) @HttpUrl String websiteUrl,
        @Size(max = 255) @HttpUrl String linkedinUrl,
        @NotNull @NotEmpty Set<@NotNull UUID> categoryIds
) {}
