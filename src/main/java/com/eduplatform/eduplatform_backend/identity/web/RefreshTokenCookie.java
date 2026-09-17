package com.eduplatform.eduplatform_backend.identity.web;

import com.eduplatform.eduplatform_backend.common.security.config.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import java.time.Duration;

/**
 * Mirrors the refresh token into an httpOnly cookie on every endpoint that issues one.
 *
 * <p>The admin portal is the SUPER_ADMIN surface, so its 30-day refresh token must not sit in
 * web storage where any script on the origin can read it. The token is still returned in the
 * response body — the public site's BFF reads it from there and keeps its own cookie — so this
 * is purely additive: a client either uses the body or ignores it and relies on the cookie.
 */
@Component
public class RefreshTokenCookie {

    /**
     * Deliberately not {@code ep_rt}: that name belongs to the public site's BFF cookie. Keeping
     * them distinct means a browser signed into both hosts never has one session clobber the other.
     */
    public static final String NAME = "ep_portal_rt";

    /**
     * Scoped to the only two endpoints that need it (/refresh and /logout) so the token is not
     * attached to every other API call the portal makes.
     */
    private static final String PATH = "/api/auth";

    private final Duration maxAge;
    private final boolean secure;

    RefreshTokenCookie(JwtProperties jwt, Environment environment,
                       @Value("${app.security.cookies.secure:auto}") String secureMode) {
        // Matches the refresh TTL exactly (same JwtProperties the token itself is minted from),
        // so the cookie never outlives the token it carries or expires while the token is valid.
        this.maxAge = Duration.ofDays(jwt.refreshTtlDays());
        this.secure = switch (secureMode.trim().toLowerCase()) {
            // "auto" = every profile but dev. Sniffing the request scheme looks more precise but
            // is wrong here: TLS terminates at the edge and the portal's nginx overwrites
            // X-Forwarded-Proto with its own $scheme (http), so a deployed instance would look
            // insecure and silently drop the flag. The dev profile is the only place the browser
            // genuinely speaks plain HTTP, and Safari discards Secure cookies on http://localhost,
            // which would make local sign-in impossible.
            case "auto" -> !environment.acceptsProfiles(Profiles.of("dev"));
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalStateException(
                    "app.security.cookies.secure must be auto, true or false — got: " + secureMode);
        };
    }

    void issue(HttpServletResponse response, String rawRefreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                base(rawRefreshToken).maxAge(maxAge).build().toString());
    }

    void clear(HttpServletResponse response) {
        // Name, path and flags must match the issued cookie: a browser replaces a cookie only on
        // that triple, so any mismatch leaves the live token sitting in the jar.
        response.addHeader(HttpHeaders.SET_COOKIE, base("").maxAge(0).build().toString());
    }

    /** The cookie's token, or {@code null} when absent or blank. */
    String read(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, NAME);
        if (cookie == null) return null;
        String value = cookie.getValue();
        return value == null || value.isBlank() ? null : value;
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .path(PATH)
                // Lax, not Strict: the portal and the API share the aztu.edu.az registrable domain,
                // so same-site holds even when the SPA is served from another subdomain, and Lax
                // still survives the top-level navigations an admin makes back into the portal.
                .sameSite("Lax");
    }
}
