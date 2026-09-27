package com.eduplatform.eduplatform_backend.review.web.dto;

import jakarta.validation.constraints.NotNull;

public record ReviewVisibilityRequest(@NotNull Boolean visible) {}
