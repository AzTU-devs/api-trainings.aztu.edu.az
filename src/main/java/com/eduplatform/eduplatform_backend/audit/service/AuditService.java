package com.eduplatform.eduplatform_backend.audit.service;

import com.eduplatform.eduplatform_backend.audit.domain.AuditLog;
import com.eduplatform.eduplatform_backend.audit.repo.AuditLogRepository;
import com.eduplatform.eduplatform_backend.audit.web.dto.AuditLogEntryDto;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Writes and reads the semantic audit trail. Domain services call
 * {@link #record} after a meaningful admin mutation; the super-admin
 * audit view reads via {@link #list} / {@link #get}.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repo;
    private final UserRepository users;

    public AuditService(AuditLogRepository repo, UserRepository users) {
        this.repo = repo;
        this.users = users;
    }

    /** Record an audit entry for the current authenticated actor. Never throws into the caller. */
    @Transactional
    public void record(String action, String entityType, UUID entityId,
                       Map<String, Object> before, Map<String, Object> after) {
        try {
            AuthenticatedPrincipal me = currentPrincipal();
            HttpServletRequest req = currentRequest();
            AuditLog row = AuditLog.builder()
                    .id(UUID.randomUUID())
                    .actorId(me == null ? null : me.userId())
                    .actorRole(me == null || me.roles().isEmpty() ? null : me.roles().iterator().next())
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .beforeData(before)
                    .afterData(after)
                    .ipAddress(req == null ? null : HttpMeta.clientIp(req))
                    .userAgent(req == null ? null : HttpMeta.userAgent(req))
                    .requestId(currentRequestId(req))
                    .occurredAt(Instant.now())
                    .build();
            repo.save(row);
        } catch (Exception ex) {
            log.warn("Failed to write audit log for action={} entity={}", action, entityType, ex);
        }
    }

    private static final Instant MIN_TS = Instant.EPOCH;
    private static final Instant MAX_TS = Instant.parse("9999-12-31T23:59:59Z");

    @Transactional(readOnly = true)
    public Page<AuditLogEntryDto> list(String search, String action, UUID actorId,
                                       String resourceType, Instant from, Instant to, Pageable pageable) {
        Page<AuditLog> page = repo.search(blank(search), blank(action), actorId, blank(resourceType),
                from == null ? MIN_TS : from, to == null ? MAX_TS : to, pageable);
        Map<UUID, User> actors = loadActors(page.getContent().stream()
                .map(AuditLog::getActorId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return page.map(a -> toDto(a, actors.get(a.getActorId())));
    }

    @Transactional(readOnly = true)
    public AuditLogEntryDto get(UUID id) {
        AuditLog a = repo.findById(id)
                .orElseThrow(() -> Errors.notFound("AUDIT_LOG_NOT_FOUND", "Audit entry does not exist"));
        User actor = a.getActorId() == null ? null : users.findById(a.getActorId()).orElse(null);
        return toDto(a, actor);
    }

    // ── mapping ─────────────────────────────────────────────────────────

    private Map<UUID, User> loadActors(java.util.Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, u -> u));
    }

    private static AuditLogEntryDto toDto(AuditLog a, User actor) {
        String name = actor == null ? null
                : (actor.getFirstName() + " " + (actor.getLastName() == null ? "" : actor.getLastName())).trim();
        return new AuditLogEntryDto(
                a.getId(), a.getActorId(), name, actor == null ? null : actor.getEmail(),
                a.getAction(), a.getEntityType(),
                a.getEntityId() == null ? null : a.getEntityId().toString(),
                a.getIpAddress(), a.getUserAgent(),
                diff(a.getBeforeData(), a.getAfterData()),
                null, a.getOccurredAt());
    }

    /** Build a per-field {from,to} diff from the before/after snapshots. */
    private static Map<String, AuditLogEntryDto.ChangePair> diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null && after == null) return null;
        Map<String, Object> b = before == null ? Map.of() : before;
        Map<String, Object> af = after == null ? Map.of() : after;
        Map<String, AuditLogEntryDto.ChangePair> out = new LinkedHashMap<>();
        java.util.Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(b.keySet());
        keys.addAll(af.keySet());
        for (String k : keys) {
            Object from = b.get(k);
            Object to = af.get(k);
            if (!Objects.equals(from, to)) {
                out.put(k, new AuditLogEntryDto.ChangePair(from, to));
            }
        }
        return out.isEmpty() ? null : out;
    }

    // ── context helpers ─────────────────────────────────────────────────

    private static AuthenticatedPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof AuthenticatedPrincipal p) ? p : null;
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private static UUID currentRequestId(HttpServletRequest req) {
        if (req == null) return null;
        Object id = req.getAttribute(HttpMeta.REQUEST_ID_ATTR);
        return id instanceof UUID u ? u : null;
    }

    private static String blank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Convenience for callers building before/after snapshots without importing Map directly. */
    public static Map<String, Object> snapshot(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** Standard audit action vocabulary mirrored by the dashboard. */
    public static final class Actions {
        public static final String CREATE = "CREATE";
        public static final String UPDATE = "UPDATE";
        public static final String DELETE = "DELETE";
        public static final String APPROVE = "APPROVE";
        public static final String REJECT = "REJECT";
        public static final String PUBLISH = "PUBLISH";
        public static final String ARCHIVE = "ARCHIVE";
        public static final String OTHER = "OTHER";
        private Actions() {}
    }
}
