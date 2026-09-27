package com.eduplatform.eduplatform_backend.audit.web.dto;

import java.time.Instant;
import java.util.UUID;

/** One entry of the IP blocklist, as the security page lists it. */
public record BlockedIpDto(
        UUID id,
        String ipAddress,
        String reason,
        UUID createdBy,
        String createdByEmail,
        Instant createdAt
) {}
