package com.eduplatform.eduplatform_backend.audit.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/** Read model for the super-admin security events view. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SecurityEventDto(
        UUID id,
        String kind,
        String severity,
        UUID actorId,
        String actorEmail,
        String ipAddress,
        String countryCode,
        String userAgent,
        String message,
        Instant occurredAt
) {}
