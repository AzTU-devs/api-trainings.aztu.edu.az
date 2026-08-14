package com.eduplatform.eduplatform_backend.tutor.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record TutorApplyRequest(
        @Size(max = 160) String headline,
        @Size(max = 5000) String bio,
        @Min(0) Short yearsExperience,
        @Size(max = 255) String websiteUrl,
        @Size(max = 255) String linkedinUrl,
        @NotNull @NotEmpty Set<UUID> categoryIds
) {}
