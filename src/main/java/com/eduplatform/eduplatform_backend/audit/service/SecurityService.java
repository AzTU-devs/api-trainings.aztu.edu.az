package com.eduplatform.eduplatform_backend.audit.service;

import com.eduplatform.eduplatform_backend.audit.domain.BlockedIp;
import com.eduplatform.eduplatform_backend.audit.domain.SecurityEvent;
import com.eduplatform.eduplatform_backend.audit.repo.SecurityEventRepository;
import com.eduplatform.eduplatform_backend.audit.web.dto.BlockedIpDto;
import com.eduplatform.eduplatform_backend.audit.web.dto.SecurityEventDto;
import com.eduplatform.eduplatform_backend.audit.web.dto.SecurityOverviewDto;
import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import com.eduplatform.eduplatform_backend.identity.service.LoginSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
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
    private final AuditService audit;
    private final LoginSecurityService loginSecurity;

    public SecurityService(SecurityEventRepository events, UserRepository users,
                           BlockedIpService blockedIps, SecurityEventRecorder recorder, AuditService audit,
                           LoginSecurityService loginSecurity) {
        this.events = events;
        this.users = users;
        this.blockedIps = blockedIps;
        this.recorder = recorder;
        this.audit = audit;
        this.loginSecurity = loginSecurity;
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
                users.countLockedOut(Instant.now()),
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

    /**
     * Blocks one client address from the whole API, login included.
     *
     * <p>Refused with 400 unless {@code ip} is a single IPv4 or IPv6 address: any string used to be
     * stored, and a range such as 10.0.0.0/24 was accepted and then never matched anything. The
     * address is stored in canonical form, the form it is compared in. Refused as well are the
     * caller's own address and loopback — blocking either locks the administrator, or everybody
     * who reaches the API through a local proxy, out of the very page that could undo it.
     */
    @Transactional
    public void blockIp(String ip, String reason, UUID adminId, HttpServletRequest req) {
        String canonical = BlockedIpService.canonical(ip).orElseThrow(() -> Errors.badRequest("INVALID_IP",
                "Enter a single IPv4 or IPv6 address, such as 203.0.113.7; ranges are not supported"));
        String own = BlockedIpService.canonical(HttpMeta.clientIp(req)).orElse(HttpMeta.clientIp(req));
        if (canonical.equals(own)) {
            throw Errors.badRequest("CANNOT_BLOCK_SELF",
                    "That is the address you are connected from; blocking it would lock you out");
        }
        if (isLoopback(canonical)) {
            throw Errors.badRequest("CANNOT_BLOCK_LOOPBACK",
                    "A loopback address is the server itself or its proxy; blocking it would lock everyone out");
        }
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        blockedIps.block(canonical, why, adminId);
        recorder.record(adminId, SecurityEventRecorder.IP_BLOCKED, req,
                Map.of("ipAddress", canonical, "reason", why == null ? "" : why));
        audit.record(AuditService.Actions.CREATE, "BLOCKED_IP", null, null,
                AuditService.snapshot("ipAddress", canonical, "reason", why));
    }

    @Transactional(readOnly = true)
    public List<BlockedIpDto> listBlockedIps() {
        List<BlockedIp> rows = blockedIps.list();
        Set<UUID> creators = rows.stream().map(BlockedIp::getCreatedBy).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, User> byId = creators.isEmpty() ? Map.of()
                : users.findAllById(creators).stream().collect(Collectors.toMap(User::getId, u -> u));
        return rows.stream().map(b -> toDto(b, byId.get(b.getCreatedBy()))).toList();
    }

    /** Lifts a block, in the table and in the in-memory mirror the requests are checked against. */
    @Transactional
    public void unblockIp(UUID id, UUID adminId, HttpServletRequest req) {
        BlockedIp row = blockedIps.unblock(id)
                .orElseThrow(() -> Errors.notFound("BLOCKED_IP_NOT_FOUND", "That address is not blocked"));
        recorder.record(adminId, SecurityEventRecorder.IP_UNBLOCKED, req, Map.of("ipAddress", row.getIpAddress()));
        audit.record(AuditService.Actions.DELETE, "BLOCKED_IP", row.getId(),
                AuditService.snapshot("ipAddress", row.getIpAddress()), null);
    }

    /**
     * Ends a failed-login lockout early: every client address's lock on the account's address
     * (see LoginSecurityService) and the account's own record of it, and turns a pre-V14 LOCKED
     * status back to ACTIVE; a SUSPENDED account stays suspended — disabling is an
     * administrator's decision, and this used to override it by setting ACTIVE unconditionally.
     */
    @Transactional
    public void unlockAccount(UUID userId, UUID adminId, HttpServletRequest req) {
        User u = users.findById(userId)
                .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));
        if (u.getStatus() == UserStatus.LOCKED) {
            u.setStatus(UserStatus.ACTIVE);
        }
        loginSecurity.releaseAll(u);
        users.save(u);
        recorder.record(userId, SecurityEventRecorder.ACCOUNT_UNLOCKED, req,
                Map.of("by", adminId.toString()));
        audit.record(AuditService.Actions.UPDATE, "USER", userId, null,
                AuditService.snapshot("unlocked", true));
    }

    private static boolean isLoopback(String canonical) {
        try {
            InetAddress a = InetAddress.getByName(canonical);   // a canonical literal: no lookup
            return a.isLoopbackAddress() || a.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static BlockedIpDto toDto(BlockedIp b, User creator) {
        return new BlockedIpDto(b.getId(), b.getIpAddress(), b.getReason(), b.getCreatedBy(),
                creator == null ? null : creator.getEmail(), b.getCreatedAt());
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
            case SecurityEventRecorder.PASSWORD_CHANGE, SecurityEventRecorder.ACCOUNT_UNLOCKED,
                 SecurityEventRecorder.IP_UNBLOCKED -> "INFO";
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
            case SecurityEventRecorder.IP_UNBLOCKED -> "IP address unblocked by an administrator";
            case SecurityEventRecorder.ACCOUNT_UNLOCKED -> "Account unlocked by an administrator";
            case SecurityEventRecorder.TOKEN_REVOKED -> "Refresh token revoked";
            default -> e.getEventType();
        };
    }

    private static String blank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
