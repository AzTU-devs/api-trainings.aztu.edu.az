package com.eduplatform.eduplatform_backend.common.security.ratelimit;

import com.eduplatform.eduplatform_backend.audit.service.HttpMeta;
import com.eduplatform.eduplatform_backend.common.error.AppException;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.web.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-IP token-bucket budget on the unauthenticated auth endpoints — the only brute-force defence
 * standing in front of the login form.
 *
 * <p>It is registered ahead of {@code JwtAuthFilter}, so it runs before the DispatcherServlet and a
 * rejected request never reaches {@code AuthService.login}. That ordering is the point: the failed
 * attempt counter in {@code LoginSecurityService} auto-locks an account after
 * {@code app.security.max-failed-logins} misses, so if throttling happened after the password
 * check, an attacker could spend a stranger's five allowed failures and have the defence lock the
 * victim out for them. Rejecting first means a throttled request costs the victim nothing.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    /** Callers with no resolvable address share a single budget instead of escaping one. */
    private static final String UNKNOWN_CLIENT = "unknown";

    private final boolean enabled;
    /** Null when the feature is off — {@link #shouldNotFilter} then skips the filter entirely. */
    private final RateLimitBucketStore store;
    private final ObjectMapper mapper;

    public AuthRateLimitFilter(RateLimitProperties props, ObjectMapper mapper) {
        this.enabled = props.enabled();
        // Disabled means no state whatsoever: no cache, no buckets, nothing to size or evict.
        this.store = this.enabled ? new RateLimitBucketStore(props) : null;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) return true;
        String path = lookupPath(request);
        return path == null || !path.startsWith(RateLimitRule.GUARDED_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        RateLimitRule rule = RateLimitRule.match(req.getMethod(), lookupPath(req));
        if (rule == null) {
            chain.doFilter(req, res);
            return;
        }

        String client = clientKey(req);
        ConsumptionProbe probe = store.bucketFor(rule, client).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(req, res);
            return;
        }
        reject(req, res, rule, client, probe.getNanosToWaitForRefill());
    }

    private void reject(HttpServletRequest req, HttpServletResponse res, RateLimitRule rule,
                        String client, long nanosToWaitForRefill) throws IOException {
        // Round up: a Retry-After of 0 invites an immediate retry that is certain to fail again.
        long retryAfterSeconds = Math.max(1L, (nanosToWaitForRefill + 999_999_999L) / 1_000_000_000L);

        // DEBUG rather than WARN: the caller decides how often this fires, so a line per rejection at
        // WARN would be a log-volume amplifier they control. Persistent abuse belongs on the IP
        // blocklist, which does log, and which this filter deliberately does not duplicate.
        log.debug("Rate limit exhausted: rule={} client={} retryAfter={}s", rule.key(), client, retryAfterSeconds);

        AppException error = Errors.tooManyRequests("RATE_LIMITED",
                "Too many requests from this address; please try again in " + retryAfterSeconds + " seconds");
        res.setStatus(error.status().value());
        // Retry-After is the only budget detail disclosed. The remaining-token count stays secret:
        // an unauthenticated caller who can read it can pace an attack to sit just under the limit.
        res.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = ApiError.of(error.status().value(), error.code(), error.getMessage(), req.getRequestURI());
        mapper.writeValue(res.getOutputStream(), body);
    }

    private static String clientKey(HttpServletRequest request) {
        String ip = HttpMeta.clientIp(request);
        return (ip == null || ip.isBlank()) ? UNKNOWN_CLIENT : ip;
    }

    /**
     * The container-normalised, context-relative path — the same form Spring matches handlers on.
     * {@code getRequestURI()} is the raw request line: it still carries percent-escapes,
     * {@code ;jsessionid} path parameters and unresolved dot segments, so matching on it would let
     * {@code /api/auth/lo%67in} reach the login handler without spending any budget.
     */
    private static String lookupPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath == null) return request.getRequestURI();
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? servletPath : servletPath + pathInfo;
    }
}
