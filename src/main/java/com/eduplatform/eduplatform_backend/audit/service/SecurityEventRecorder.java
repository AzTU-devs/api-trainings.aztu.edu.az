package com.eduplatform.eduplatform_backend.audit.service;

import com.eduplatform.eduplatform_backend.audit.domain.SecurityEvent;
import com.eduplatform.eduplatform_backend.audit.repo.SecurityEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Persists security events (failed logins, lockouts, IP blocks, …). Writes run in their own
 * transaction so they survive a rollback of the surrounding action (e.g. a failed login attempt).
 */
@Service
public class SecurityEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(SecurityEventRecorder.class);

    // Event kinds (mirror the dashboard's SecurityEventKind union).
    public static final String FAILED_LOGIN = "FAILED_LOGIN";
    public static final String LOCKOUT = "LOCKOUT";
    public static final String PASSWORD_CHANGE = "PASSWORD_CHANGE";
    public static final String TOKEN_REVOKED = "TOKEN_REVOKED";
    public static final String SUSPICIOUS_LOGIN = "SUSPICIOUS_LOGIN";
    public static final String IP_BLOCKED = "IP_BLOCKED";
    public static final String ACCOUNT_UNLOCKED = "ACCOUNT_UNLOCKED";

    private final SecurityEventRepository repo;

    public SecurityEventRecorder(SecurityEventRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID userId, String kind, HttpServletRequest req, Map<String, Object> detail) {
        try {
            SecurityEvent e = SecurityEvent.builder()
                    .id(UUID.randomUUID())
                    .userId(userId)
                    .eventType(kind)
                    .ipAddress(req == null ? null : HttpMeta.clientIp(req))
                    .userAgent(req == null ? null : HttpMeta.userAgent(req))
                    .detail(detail)
                    .occurredAt(Instant.now())
                    .build();
            repo.save(e);
        } catch (Exception ex) {
            log.warn("Failed to write security event kind={} user={}", kind, userId, ex);
        }
    }
}
