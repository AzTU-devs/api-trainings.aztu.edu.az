package com.eduplatform.eduplatform_backend.common;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-address login budget (10 a minute), keyed on the client address Tomcat's RemoteIpValve
 * resolves. The test client connects from loopback, which is one of the default trusted internal
 * proxies, so it stands in for nginx, the admin container or the public site's BFF: whatever it
 * puts in X-Forwarded-For is read the way a real proxy's header is, right to left.
 *
 * <p>Each test keys its requests on its own client address, so the buckets never overlap.
 */
@TestPropertySource(properties = {
        "app.ratelimit.enabled=true",
        // Retired. Existing .env files still carry it, so it must neither break startup nor change
        // which address the limiter uses; when true it used to hand out a fresh bucket per
        // X-Forwarded-For value.
        "app.security.trust-forward-headers=true",
})
class AuthRateLimitTest extends AbstractIntegrationTest {

    /** RateLimitRule.LOGIN's default capacity. */
    private static final int LOGIN_BUDGET = 10;

    @Test
    void theEleventhLoginFromOneAddressWithinAMinuteIsThrottled() {
        for (int attempt = 1; attempt <= LOGIN_BUDGET; attempt++) {
            failedLogin().send().expectError(401, "INVALID_CREDENTIALS");
        }

        ApiClient.Response throttled = failedLogin().send().expectError(429, "RATE_LIMITED");

        assertThat(throttled.header("Retry-After")).isPresent();
    }

    /**
     * nginx appends the address it saw to whatever X-Forwarded-For the client sent, so the leftmost
     * entry is the client's own invention. The limiter used to key on it, so rotating it gave a
     * fresh bucket per request, straight through the proxy.
     */
    @Test
    void rotatingTheLeftmostForwardedForEntryDoesNotEarnAFreshBucket() {
        String client = "203.0.113.77";
        for (int attempt = 1; attempt <= LOGIN_BUDGET; attempt++) {
            failedLogin().header("X-Forwarded-For", "198.51.100." + attempt + ", " + client)
                    .send().expectError(401, "INVALID_CREDENTIALS");
        }

        failedLogin().header("X-Forwarded-For", "198.51.100.200, " + client)
                .send().expectError(429, "RATE_LIMITED");
        // Neither header is consulted at all; each used to be another way to a fresh bucket.
        failedLogin().header("X-Forwarded-For", client).header("Forwarded", "for=198.51.100.201")
                .send().expectError(429, "RATE_LIMITED");
        failedLogin().header("X-Forwarded-For", client).header("X-Real-IP", "198.51.100.202")
                .send().expectError(429, "RATE_LIMITED");
    }

    /**
     * The other half of the contract: forwarded addresses from a trusted proxy are still honoured,
     * so users behind the admin container or the BFF do not all share one bucket.
     */
    @Test
    void aDifferentClientBehindATrustedProxyHasItsOwnBudget() {
        for (int attempt = 1; attempt <= LOGIN_BUDGET; attempt++) {
            failedLogin().header("X-Forwarded-For", "203.0.113.90").send().expectError(401, "INVALID_CREDENTIALS");
        }
        failedLogin().header("X-Forwarded-For", "203.0.113.90").send().expectError(429, "RATE_LIMITED");

        failedLogin().header("X-Forwarded-For", "203.0.113.91").send().expectError(401, "INVALID_CREDENTIALS");
    }

    /** An address with no account behind it, so no failed-login counter or lockout is touched. */
    private ApiClient.Call failedLogin() {
        return api.post("/api/auth/login")
                .json(Json.object("email", "nobody-" + word() + "@it.eduplatform.test", "password", "wrong-password"));
    }
}
