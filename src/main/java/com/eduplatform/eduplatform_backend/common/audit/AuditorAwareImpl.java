package com.eduplatform.eduplatform_backend.common.audit;

import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the current user id from the security context for {@code @CreatedBy}/{@code @LastModifiedBy}.
 * Returns empty for anonymous/system operations — the columns are then left as the previous value
 * (or NULL on insert), which is acceptable for migrations / bootstrap data.
 */
@Component("auditorAware")
public class AuditorAwareImpl implements AuditorAware<UUID> {

    @Override
    public Optional<UUID> getCurrentAuditor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        if (auth.getPrincipal() instanceof AuthenticatedPrincipal p) return Optional.ofNullable(p.userId());
        return Optional.empty();
    }
}
