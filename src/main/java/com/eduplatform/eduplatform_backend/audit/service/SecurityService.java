package com.eduplatform.eduplatform_backend.audit.service;

import com.eduplatform.eduplatform_backend.audit.domain.SecurityEvent;
import com.eduplatform.eduplatform_backend.audit.repo.SecurityEventRepository;
import com.eduplatform.eduplatform_backend.audit.web.dto.SecurityEventDto;
import com.eduplatform.eduplatform_backend.audit.web.dto.SecurityOverviewDto;
import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read + administrative actions backing the super-admin security view. */
@Service
public class SecurityService {

    private final SecurityEventRepository events;
    private final UserRepository users;
    private final BlockedIpService blockedIps;
    private final SecurityEventRecorder recorder;

    public SecurityService(SecurityEventRepository events, UserRepository users,
                           BlockedIpService blockedIps, SecurityEventRecorder recorder) {
        this.events = events;
        this.users = users;
        this.blockedIps = blockedIps;
        this.recorder = recorder;
    }

    @Transactional(readOnly = true)
    public SecurityOverviewDto overview() {
        Instant since24h = Instant.now().minus(24, ChronoUnit.HOURS);
        Instant since7d = Instant.now().minus(7, ChronoUnit.DAYS);

        List<SecurityEvent> recent = events.search(null, null, PageRequest.of(0, 10)).getContent();
        Map<UUID, User> actors = loadActors(recent);
        List<SecurityEventDto> recentDtos = recent.stream().map(e -> toDto(e, actors.get(e.getUserId()))).toList();

        List<SecurityOverviewDto.TopOffender> offenders = events
                .topOffenders(SecurityEventRecorder.FAILED_LOGIN, since7d, PageRequest.of(0, 5)).stream()
                .map(v -> new SecurityOverviewDto.TopOffender(v.getIp(), v.getCnt(), null))
                .toList();

        return new SecurityOverviewDto(
                events.countByEventTypeAndOccurredAtAfter(SecurityEventRecorder.FAILED_LOGIN, since24h),
                users.countByStatus(UserStatus.LOCKED),
                events.countByEventTypeAndOccurredAtAfter(SecurityEventRecorder.SUSPICIOUS_LOGIN, since24h),
                blockedIps.count(),
                recentDtos,
                offenders);
    }

    @Transactional(readOnly = true)
    public Page<SecurityEventDto> listEvents(String kind, String search, Pageable pageable) {
        Page<SecurityEvent> page = events.search(blank(kind), blank(search), pageable);
        Map<UUID, User> actors = loadActors(page.getContent());
        return page.map(e -> toDto(e, actors.get(e.getUserId())));
    }

    @Transactional
    public void blockIp(String ip, String reason, UUID adminId, HttpServletRequest req) {
        if (ip == null || ip.isBlank()) {
            throw Errors.badRequest("IP_REQUIRED", "ipAddress is required");
        }
        blockedIps.block(ip.trim(), reason, adminId);
        recorder.record(adminId, SecurityEventRecorder.IP_BLOCKED, req,
                Map.of("ipAddress", ip.trim(), "reason", reason == null ? "" : reason));
    }

    @Transactional
    public void unlockAccount(UUID userId, UUID adminId, HttpServletRequest req) {
        User u = users.findById(userId)
                .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));
        u.setStatus(UserStatus.ACTIVE);
        u.setFailedLogins((short) 0);
        users.save(u);
        recorder.record(userId, SecurityEventRecorder.ACCOUNT_UNLOCKED, req,
                Map.of("by", adminId.toString()));
    }

    // ── mapping ─────────────────────────────────────────────────────────

    private Map<UUID, User> loadActors(List<SecurityEvent> evs) {
        Set<UUID> ids = evs.stream().map(SecurityEvent::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, u -> u));
    }

    private static SecurityEventDto toDto(SecurityEvent e, User actor) {
        return new SecurityEventDto(
                e.getId(), e.getEventType(), severityOf(e.getEventType()),
                e.getUserId(), actor == null ? null : actor.getEmail(),
                e.getIpAddress(), null, e.getUserAgent(),
                messageOf(e), e.getOccurredAt());
    }

    private static String severityOf(String kind) {
        return switch (kind == null ? "" : kind) {
            case SecurityEventRecorder.LOCKOUT, SecurityEventRecorder.SUSPICIOUS_LOGIN,
                 SecurityEventRecorder.IP_BLOCKED -> "HIGH";
            case SecurityEventRecorder.FAILED_LOGIN, SecurityEventRecorder.TOKEN_REVOKED -> "LOW";
            case SecurityEventRecorder.PASSWORD_CHANGE, SecurityEventRecorder.ACCOUNT_UNLOCKED -> "INFO";
            default -> "MEDIUM";
        };
    }

    private static String messageOf(SecurityEvent e) {
        Object msg = e.getDetail() == null ? null : e.getDetail().get("message");
        if (msg != null) return String.valueOf(msg);
        return switch (e.getEventType() == null ? "" : e.getEventType()) {
            case SecurityEventRecorder.FAILED_LOGIN -> "Failed login attempt";
            case SecurityEventRecorder.LOCKOUT -> "Account locked after repeated failed logins";
            case SecurityEventRecorder.IP_BLOCKED -> "IP address blocked by an administrator";
            case SecurityEventRecorder.ACCOUNT_UNLOCKED -> "Account unlocked by an administrator";
            case SecurityEventRecorder.TOKEN_REVOKED -> "Refresh token revoked";
            default -> e.getEventType();
        };
    }

    private static String blank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
