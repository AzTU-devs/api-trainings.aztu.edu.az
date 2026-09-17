package com.eduplatform.eduplatform_backend.common.security.ratelimit;

import org.springframework.http.HttpMethod;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The unauthenticated auth endpoints that carry a per-IP budget, with the default budget of each.
 *
 * <p>The defaults are sized to be invisible to a human and hostile to a script: nobody needs a
 * second password-reset mail twenty minutes after the first, while credential stuffing only pays
 * off at thousands of attempts a minute. Every default is overridable under
 * {@code app.ratelimit.rules.<key>} — campus traffic arrives NATed behind a handful of public
 * addresses, so a specific deployment may legitimately need a larger signup budget.
 */
public enum RateLimitRule {

    /** The credential-stuffing target. 10/min still leaves a forgetful human several tries. */
    LOGIN("login", HttpMethod.POST, "/api/auth/login",
            10, Duration.ofMinutes(1)),

    /** Automated signups are cheap to create and expensive to clean up, hence an hour-long window. */
    REGISTER("register", HttpMethod.POST, "/api/auth/register",
            5, Duration.ofHours(1)),

    /** Sends an OTP mail, so the budget guards the outbound mail reputation as much as the account. */
    REGISTER_TUTOR_START("register-tutor-start", HttpMethod.POST, "/api/auth/register/tutor/start",
            5, Duration.ofHours(1)),

    /** OTP guessing is already capped per signup; this only bounds the volume one address can drive. */
    REGISTER_TUTOR_VERIFY("register-tutor-verify", HttpMethod.POST, "/api/auth/register/tutor/verify",
            10, Duration.ofHours(1)),

    /** Same mail cost as the tutor flow, on an endpoint that mints privileged accounts. */
    ADMIN_REGISTER_START("admin-register-start", HttpMethod.POST, "/api/auth/admin/register/start",
            5, Duration.ofHours(1)),

    ADMIN_REGISTER_VERIFY("admin-register-verify", HttpMethod.POST, "/api/auth/admin/register/verify",
            10, Duration.ofHours(1)),

    /** Mails an address the caller merely claims to own — the tightest budget in the set. */
    PASSWORD_FORGOT("password-forgot", HttpMethod.POST, "/api/auth/password/forgot",
            3, Duration.ofHours(1)),

    /** Reset tokens are high-entropy; 10/h covers a mistyped copy-paste, not a search. */
    PASSWORD_RESET("password-reset", HttpMethod.POST, "/api/auth/password/reset",
            10, Duration.ofHours(1)),

    /**
     * Refresh is called by every open tab whenever a 15-minute access token expires, and again
     * after each cold start, so the budget has to absorb bursts from one shared office address.
     */
    REFRESH("refresh", HttpMethod.POST, "/api/auth/refresh",
            60, Duration.ofMinutes(1));

    /** Every rule lives under this prefix, which lets the filter skip all other traffic with one check. */
    public static final String GUARDED_PATH_PREFIX = "/api/auth/";

    private static final Map<String, RateLimitRule> BY_METHOD_AND_PATH = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(RateLimitRule::matchKey, Function.identity()));

    static {
        // A rule added outside the prefix would be silently unenforced, because the filter never
        // looks at such paths. Fail at startup instead of discovering it after an incident.
        for (RateLimitRule rule : values()) {
            if (!rule.path.startsWith(GUARDED_PATH_PREFIX)) {
                throw new IllegalStateException(
                        "RateLimitRule " + rule.name() + " must live under " + GUARDED_PATH_PREFIX);
            }
        }
    }

    private final String key;
    private final HttpMethod method;
    private final String path;
    private final int defaultCapacity;
    private final Duration defaultWindow;

    RateLimitRule(String key, HttpMethod method, String path, int defaultCapacity, Duration defaultWindow) {
        this.key = key;
        this.method = method;
        this.path = path;
        this.defaultCapacity = defaultCapacity;
        this.defaultWindow = defaultWindow;
    }

    /** Kebab-case identifier used by {@code app.ratelimit.rules.<key>} overrides and by log lines. */
    public String key() { return key; }

    public int defaultCapacity() { return defaultCapacity; }

    public Duration defaultWindow() { return defaultWindow; }

    private String matchKey() { return method.name() + ' ' + path; }

    /** The rule guarding {@code method path}, or {@code null} when that request is not rate limited. */
    public static RateLimitRule match(String method, String path) {
        if (method == null || path == null) return null;
        return BY_METHOD_AND_PATH.get(method.toUpperCase(Locale.ROOT) + ' ' + path);
    }
}
