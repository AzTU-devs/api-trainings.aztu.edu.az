package com.eduplatform.eduplatform_backend.audit.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/** Read model for the super-admin API logs view. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiLogEntryDto(
        UUID id,
        String method,
        String path,
        int status,
        long latencyMs,
        String ipAddress,
        String userAgent,
        UUID actorId,
        String actorEmail,
        UUID requestId,
        String errorMessage,
        Long responseBytes,
        Instant occurredAt
) {}
