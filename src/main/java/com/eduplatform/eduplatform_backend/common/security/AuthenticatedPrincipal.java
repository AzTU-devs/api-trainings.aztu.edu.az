package com.eduplatform.eduplatform_backend.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * Lightweight principal carried by the JWT auth filter.
 * Wired into Spring's {@code Authentication.getPrincipal()}.
 */
public record AuthenticatedPrincipal(
        UUID userId,
        String email,
        Set<String> roles,
        Set<String> permissions
) {}
