package com.eduplatform.eduplatform_backend.audit.web;

import com.eduplatform.eduplatform_backend.audit.domain.ApiRequestLog;
import com.eduplatform.eduplatform_backend.audit.service.ApiLogService;
import com.eduplatform.eduplatform_backend.audit.service.BlockedIpService;
import com.eduplatform.eduplatform_backend.audit.service.HttpMeta;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Tags each request with an id, enforces the IP blocklist, and persists an
 * {@link ApiRequestLog} on completion (backing the super-admin API logs view).
 */
@Component
public class ApiAccessLogInterceptor implements HandlerInterceptor {

    private static final String START_ATTR = "ep.startNanos";

    private final ApiLogService apiLogs;
    private final BlockedIpService blockedIps;

    public ApiAccessLogInterceptor(ApiLogService apiLogs, BlockedIpService blockedIps) {
        this.apiLogs = apiLogs;
        this.blockedIps = blockedIps;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String ip = HttpMeta.clientIp(request);
        if (blockedIps.isBlocked(ip)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"status\":403,\"code\":\"IP_BLOCKED\",\"message\":\"Your IP address is blocked\"}");
            return false;
        }
        request.setAttribute(START_ATTR, System.nanoTime());
        UUID requestId = UUID.randomUUID();
        request.setAttribute(HttpMeta.REQUEST_ID_ATTR, requestId);
        response.setHeader("X-Request-Id", requestId.toString());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/")) return;
        if (path.startsWith("/api/super/api-logs")) return; // don't log reads of the log view itself

        // Public media is anonymous, unauthenticated and fetched as a page subresource, so it
        // is the highest-volume route here by a wide margin: one ordinary 12-card catalogue
        // page is 12 requests from a single cold visitor. Since ApiLogService.record writes
        // synchronously on the request thread, logging it would turn a static thumbnail fetch
        // into a database INSERT — an unauthenticated write-amplification vector, and one the
        // auth rate limiter does not cover because it only guards /api/auth/.
        //
        // Nothing is lost by skipping it: these are cacheable public bytes, and access
        // patterns for them belong in the web server's access log, not the audit trail.
        if (path.startsWith("/api/public/media/")) return;

        Object start = request.getAttribute(START_ATTR);
        long latencyMs = start instanceof Long s ? (System.nanoTime() - s) / 1_000_000 : 0;
        AuthenticatedPrincipal me = currentPrincipal();
        Object rid = request.getAttribute(HttpMeta.REQUEST_ID_ATTR);

        ApiRequestLog row = ApiRequestLog.builder()
                .id(UUID.randomUUID())
                .method(request.getMethod())
                .path(path.length() > 512 ? path.substring(0, 512) : path)
                .status(response.getStatus())
                .latencyMs(latencyMs)
                .ipAddress(HttpMeta.clientIp(request))
                .userAgent(HttpMeta.userAgent(request))
                .actorId(me == null ? null : me.userId())
                .actorEmail(me == null ? null : me.email())
                .requestId(rid instanceof UUID u ? u : null)
                .errorMessage(ex == null ? null : truncate(ex.getMessage()))
                .occurredAt(Instant.now())
                .build();
        apiLogs.record(row);
    }

    private static AuthenticatedPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof AuthenticatedPrincipal p) ? p : null;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
