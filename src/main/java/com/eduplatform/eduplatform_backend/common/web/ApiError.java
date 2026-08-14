package com.eduplatform.eduplatform_backend.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RFC-7807-compatible error body returned by {@code GlobalExceptionHandler}.
 * {@code code} is a stable machine identifier (e.g. {@code COURSE_NOT_FOUND}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        int status,
        String code,
        String message,
        String path,
        UUID requestId,
        Instant timestamp,
        List<FieldErrorItem> errors
) {
    public static ApiError of(int status, String code, String message, String path) {
        return new ApiError(status, code, message, path, null, Instant.now(), null);
    }

    public static ApiError validation(String path, List<FieldErrorItem> errors) {
        return new ApiError(400, "VALIDATION_FAILED", "Request validation failed", path, null, Instant.now(), errors);
    }
}
