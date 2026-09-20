package com.eduplatform.eduplatform_backend.identity;

import com.eduplatform.eduplatform_backend.common.security.TokenHasher;
import com.eduplatform.eduplatform_backend.common.security.config.JwtProperties;
import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /api/auth/logout needs no access token: it revokes only the refresh token it is handed and
 * always clears the cookie. Requiring a bearer token meant a user whose access token had expired
 * could not sign out, and the httpOnly cookie, which scripts cannot delete, stayed behind.
 *
 * <p>A bearer that is expired or otherwise invalid is ignored on logout alone. The dashboard sends
 * its bearer with every call, logout included, and everywhere else a bad one is still a 401: on
 * /api/public/** that 401 is what makes the dashboard refresh and retry.
 */
class LogoutTest extends AbstractIntegrationTest {

    private static final String COOKIE = "ep_portal_rt";

    @Autowired
    private JwtProperties jwt;

    @Test
    void logoutWithOnlyTheRefreshCookieRevokesItAndClearsTheCookie() {
        ApiClient.Response login = loginResponse(newUser("leaver", "USER").email(), PASSWORD).expectStatus(200);
        String token = cookieValue(login.setCookie(COOKIE).orElseThrow(
                () -> new AssertionError("login set no " + COOKIE + " cookie")));
        assertThat(token).isEqualTo(login.data().path("refreshToken").asText());

        ApiClient.Response logout = api.post("/api/auth/logout").cookie(COOKIE, token).send();

        assertThat(logout.status()).as("logout status, body: %s", logout.body()).isBetween(200, 299);
        assertCookieCleared(logout);
        assertThat(jdbc.queryForObject("select revoked_at is not null from refresh_tokens where token_hash = ?",
                Boolean.class, TokenHasher.sha256Hex(token))).as("token revoked").isTrue();
        api.post("/api/auth/refresh").json(Json.object("refreshToken", token)).send().expectStatus(401);
    }

    /** Idempotent: with nothing to revoke there is still a cookie jar to clear. */
    @Test
    void logoutWithNoTokenAtAllStillClearsTheCookie() {
        ApiClient.Response logout = api.post("/api/auth/logout").send();

        assertThat(logout.status()).as("logout status, body: %s", logout.body()).isBetween(200, 299);
        assertCookieCleared(logout);
    }

    /**
     * JwtAuthFilter used to answer the expired bearer with 401 before the controller ran, so unless
     * a refresh happened to succeed first, the refresh token stayed live and the cookie stayed put.
     */
    @Test
    void logoutWithAnExpiredBearerStillRevokesTheCookiesTokenAndClearsIt() {
        TestUser user = newUser("lapsed", "USER");
        ApiClient.Response login = loginResponse(user.email(), PASSWORD).expectStatus(200);
        String token = cookieValue(login.setCookie(COOKIE).orElseThrow(
                () -> new AssertionError("login set no " + COOKIE + " cookie")));

        ApiClient.Response logout = api.post("/api/auth/logout")
                .bearer(accessToken(user, Instant.now().minus(Duration.ofMinutes(5))))
                .cookie(COOKIE, token)
                .send();

        assertThat(logout.status()).as("logout status, body: %s", logout.body()).isBetween(200, 299);
        assertCookieCleared(logout);
        assertThat(jdbc.queryForObject("select revoked_at is not null from refresh_tokens where token_hash = ?",
                Boolean.class, TokenHasher.sha256Hex(token))).as("token revoked").isTrue();
    }

    @Test
    void logoutWithAMalformedBearerStillClearsTheCookie() {
        ApiClient.Response logout = api.post("/api/auth/logout").bearer("not-a-jwt").send();

        assertThat(logout.status()).as("logout status, body: %s", logout.body()).isBetween(200, 299);
        assertCookieCleared(logout);
    }

    /**
     * The token is built the way the API signs its own, which the first request proves: the same
     * construction is accepted while unexpired, so the 401s that follow are about expiry alone.
     */
    @Test
    void anExpiredBearerIsStillRejectedEverywhereElse() {
        TestUser user = newUser("lapsed", "USER");
        api.get("/api/auth/me").bearer(accessToken(user, Instant.now().plus(Duration.ofMinutes(5))))
                .send().expectStatus(200);

        String expired = accessToken(user, Instant.now().minus(Duration.ofMinutes(5)));

        api.get("/api/public/courses").bearer(expired).send().expectError(401, "INVALID_TOKEN");
        api.get("/api/auth/me").bearer(expired).send().expectError(401, "INVALID_TOKEN");
    }

    /** An access token for {@code user}, signed with the context's key, that expires at {@code expiresAt}. */
    private String accessToken(TestUser user, Instant expiresAt) {
        Instant issuedAt = expiresAt.minus(Duration.ofMinutes(jwt.accessTtlMinutes()));
        return Jwts.builder()
                .issuer(jwt.issuer())
                .subject(user.id().toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .id(UUID.randomUUID().toString())
                .claim("email", user.email())
                .claim("roles", List.of("USER"))
                .claim("perms", List.of())
                .signWith(Keys.hmacShaKeyFor(jwt.accessSecret().getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }

    private static void assertCookieCleared(ApiClient.Response logout) {
        String cleared = logout.setCookie(COOKIE).orElseThrow(
                () -> new AssertionError("logout did not clear " + COOKIE + ": " + logout.headers()));
        // Name and path must match the issued cookie, or the browser keeps the live one.
        assertThat(cleared).startsWith(COOKIE + "=;").contains("Path=/api/auth").contains("Max-Age=0");
    }

    private static String cookieValue(String setCookie) {
        int start = setCookie.indexOf('=') + 1;
        int end = setCookie.indexOf(';');
        return end < 0 ? setCookie.substring(start) : setCookie.substring(start, end);
    }
}
