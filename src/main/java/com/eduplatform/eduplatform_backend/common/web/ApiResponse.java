package com.eduplatform.eduplatform_backend.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Uniform success envelope. Errors use {@link ApiError} (RFC-7807-compatible).
 *
 * Example:
 * <pre>{ "data": {...}, "meta": null, "timestamp": "2026-05-27T..." }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(T data, Object meta, Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, null, Instant.now());
    }

    public static <T> ApiResponse<T> ok(T data, Object meta) {
        return new ApiResponse<>(data, meta, Instant.now());
    }
}
