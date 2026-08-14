package com.eduplatform.eduplatform_backend.notification.ws;

import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import org.springframework.security.core.Authentication;

import java.security.Principal;

/**
 * Principal that exposes the user's UUID as {@link #getName()}. Spring's user-destination
 * resolver uses {@code getName()} to route {@code convertAndSendToUser(userId, …)} messages.
 *
 * Carries the original {@link Authentication} so downstream message handlers can still
 * read roles/permissions via {@code SimpMessageHeaderAccessor.getUser()}.
 */
public record StompPrincipal(String name, Authentication authentication) implements Principal {

    @Override public String getName() { return name; }

    public static StompPrincipal of(Authentication auth) {
        if (auth.getPrincipal() instanceof AuthenticatedPrincipal p) {
            return new StompPrincipal(p.userId().toString(), auth);
        }
        throw new IllegalStateException("Authentication principal must be AuthenticatedPrincipal");
    }
}
