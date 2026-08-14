package com.eduplatform.eduplatform_backend.review.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewCreateRequest(
        @NotNull @Min(1) @Max(5) Short rating,
        @Size(max = 160) String title,
        @Size(max = 5000) String body
) {}
