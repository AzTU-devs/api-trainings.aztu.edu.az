package com.eduplatform.eduplatform_backend.audit.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Read model for the super-admin audit log view. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditLogEntryDto(
        UUID id,
        UUID actorId,
        String actorName,
        String actorEmail,
        String action,
        String resourceType,
        String resourceId,
        String ipAddress,
        String userAgent,
        Map<String, ChangePair> changes,
        Map<String, Object> context,
        Instant occurredAt
) {
    public record ChangePair(Object from, Object to) {}
}
