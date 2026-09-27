package com.eduplatform.eduplatform_backend.identity;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard's refresh cookie is honoured only for the dashboard. CORS allows credentials for
 * the public site as well, and the browser attaches the cookie to any request that CORS lets
 * through, so a script on the public site could otherwise refresh an administrator's session
 * and read the new tokens out of the response.
 *
 * <p>The tests reach the API as localhost. http://127.0.0.1:3000 is an allowed CORS origin on
 * another host, as the public site is in production; the dashboard is the configured portal,
 * http://localhost:3001, and in production reaches the API through its own nginx, on its own host.
 */
class RefreshCookieOriginTest extends AbstractIntegrationTest {

    private static final String COOKIE = "ep_portal_rt";

    @Test
    void theCookieRefreshesOnlyFromThePortalsOrigin() {
        String cookie = cookieFromLogin();

        // An origin CORS allows, on another host, and not the dashboard's.
        api.post("/api/auth/refresh").cookie(COOKIE, cookie).header("Origin", "http://127.0.0.1:3000")
                .send().expectError(403, "REFRESH_ORIGIN_FORBIDDEN");
        api.post("/api/auth/refresh").cookie(COOKIE, cookie).header("Sec-Fetch-Site", "same-site")
                .send().expectError(403, "REFRESH_ORIGIN_FORBIDDEN");

        ApiClient.Response ok = api.post("/api/auth/refresh").cookie(COOKIE, cookie)
                .header("Origin", "http://localhost:3001").send().expectStatus(200);
        assertThat(ok.data().path("accessToken").asText()).isNotBlank();
        // The dashboard behind its own nginx: the request's own host, whatever the portal URL says.
        String rotated = cookieValue(ok.setCookie(COOKIE).orElseThrow());
        api.post("/api/auth/refresh").cookie(COOKIE, rotated).header("Origin", "http://localhost:3000")
                .send().expectStatus(200);
    }

    /** The public site's BFF posts its token in the body, from no browser origin at all. */
    @Test
    void aTokenInTheBodyIsUnaffected() {
        String token = loginResponse(newUser("bff", "USER").email(), PASSWORD).expectStatus(200)
                .data().path("refreshToken").asText();

        api.post("/api/auth/refresh").header("Origin", "http://127.0.0.1:3000")
                .json(com.eduplatform.eduplatform_backend.support.Json.object("refreshToken", token))
                .send().expectStatus(200);
    }

    private String cookieFromLogin() {
        return cookieValue(loginResponse(newUser("portal", "ADMIN").email(), PASSWORD).expectStatus(200)
                .setCookie(COOKIE).orElseThrow());
    }

    private static String cookieValue(String setCookie) {
        return setCookie.substring(COOKIE.length() + 1, setCookie.indexOf(';'));
    }
}
