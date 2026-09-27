package com.eduplatform.eduplatform_backend.identity.web;

import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.identity.service.AdminSignupService;
import com.eduplatform.eduplatform_backend.identity.service.AuthService;
import com.eduplatform.eduplatform_backend.identity.service.TutorSignupService;
import com.eduplatform.eduplatform_backend.identity.web.dto.AdminRegisterStartRequest;
import com.eduplatform.eduplatform_backend.identity.web.dto.AdminRegisterStartResponse;
import com.eduplatform.eduplatform_backend.identity.web.dto.AdminRegisterVerifyRequest;
import com.eduplatform.eduplatform_backend.identity.web.dto.AuthTokens;
import com.eduplatform.eduplatform_backend.identity.web.dto.LoginRequest;
import com.eduplatform.eduplatform_backend.identity.web.dto.OtpStartResponse;
import com.eduplatform.eduplatform_backend.identity.web.dto.RefreshRequest;
import com.eduplatform.eduplatform_backend.identity.web.dto.RegisterRequest;
import com.eduplatform.eduplatform_backend.identity.web.dto.TutorRegisterRequest;
import com.eduplatform.eduplatform_backend.identity.web.dto.TutorRegisterResult;
import com.eduplatform.eduplatform_backend.identity.web.dto.TutorRegisterVerifyRequest;
import com.eduplatform.eduplatform_backend.identity.web.dto.UserDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Registration, login, refresh, logout, current user")
public class AuthController {

    private final AuthService auth;
    private final AdminSignupService adminSignup;
    private final TutorSignupService tutorSignup;
    private final com.eduplatform.eduplatform_backend.identity.service.AccountRecoveryService recovery;
    private final RefreshTokenCookie refreshCookie;
    private final String portalOrigin;

    public AuthController(AuthService auth, AdminSignupService adminSignup, TutorSignupService tutorSignup,
                          com.eduplatform.eduplatform_backend.identity.service.AccountRecoveryService recovery,
                          RefreshTokenCookie refreshCookie,
                          @Value("${app.frontend.portal-url:http://localhost:3001}") String portalUrl) {
        this.auth = auth;
        this.adminSignup = adminSignup;
        this.tutorSignup = tutorSignup;
        this.recovery = recovery;
        this.refreshCookie = refreshCookie;
        this.portalOrigin = originOf(portalUrl);
    }

    @PostMapping("/password/forgot")
    @Operation(summary = "Request a password-reset email (always returns 202 to avoid account enumeration)", security = {})
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody com.eduplatform.eduplatform_backend.identity.web.dto.ForgotPasswordRequest req) {
        recovery.requestPasswordReset(req.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password/reset")
    @Operation(summary = "Reset a password using the emailed token", security = {})
    public ResponseEntity<Void> resetPassword(
            @Valid @RequestBody com.eduplatform.eduplatform_backend.identity.web.dto.ResetPasswordRequest req) {
        recovery.confirmPasswordReset(req.token(), req.password());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/change")
    @Operation(summary = "Change my password",
            description = "Requires the current password (400 INVALID_CURRENT_PASSWORD otherwise; wrong ones "
                    + "count towards the sign-in lockout). The new one follows the sign-up rule. Every other "
                    + "session is signed out; the one presenting its refresh token (body, or the dashboard's "
                    + "cookie) stays signed in.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody com.eduplatform.eduplatform_backend.identity.web.dto.ChangePasswordRequest req,
            @CurrentUser AuthenticatedPrincipal me, HttpServletRequest http) {
        String keep = req.refreshToken() == null || req.refreshToken().isBlank()
                ? refreshCookie.read(http) : req.refreshToken();
        auth.changePassword(me.userId(), req.currentPassword(), req.newPassword(), keep, http);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/email/verify/request")
    @Operation(summary = "Send an email-verification link to my address",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> requestEmailVerify(@CurrentUser AuthenticatedPrincipal me) {
        recovery.requestEmailVerification(me.userId());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/email/verify/confirm")
    @Operation(summary = "Confirm an email address using the emailed token", security = {})
    public ResponseEntity<Void> confirmEmailVerify(
            @Valid @RequestBody com.eduplatform.eduplatform_backend.identity.web.dto.VerifyEmailRequest req) {
        recovery.confirmEmailVerification(req.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/register")
    @Operation(summary = "Register a new end-user account", security = {})
    public ResponseEntity<ApiResponse<AuthTokens>> register(@Valid @RequestBody RegisterRequest req,
                                                            HttpServletRequest http,
                                                            HttpServletResponse response) {
        AuthTokens tokens = auth.register(req, http);
        refreshCookie.issue(response, tokens.refreshToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(tokens));
    }

    @PostMapping("/register/tutor/start")
    @Operation(
            summary = "Begin tutor self-registration; sends an OTP to the supplied email",
            description = "Open endpoint. Submits account + tutor-profile details; an OTP is generated " +
                    "and (in production) emailed. Submit it to /register/tutor/verify within 10 minutes.",
            security = {})
    public ResponseEntity<ApiResponse<OtpStartResponse>> tutorStart(
            @Valid @RequestBody TutorRegisterRequest req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(tutorSignup.start(req)));
    }

    @PostMapping("/register/tutor/verify")
    @Operation(
            summary = "Verify the OTP and submit the tutor application",
            description = "Creates the account (USER role) and a PENDING tutor profile awaiting admin " +
                    "approval. No tokens are issued — the tutor signs in via the portal after approval.",
            security = {})
    public ResponseEntity<ApiResponse<TutorRegisterResult>> tutorVerify(
            @Valid @RequestBody TutorRegisterVerifyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(tutorSignup.verify(req)));
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate with email + password", security = {})
    public ApiResponse<AuthTokens> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http,
                                         HttpServletResponse response) {
        AuthTokens tokens = auth.login(req, http);
        refreshCookie.issue(response, tokens.refreshToken());
        return ApiResponse.ok(tokens);
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Rotate the refresh token and obtain a new access token",
            description = "Takes the token from the request body, or from the " + RefreshTokenCookie.NAME +
                    " cookie when the body omits it. The rotated token is always returned in the body too.",
            security = {})
    public ApiResponse<AuthTokens> refresh(@RequestBody(required = false) RefreshRequest req,
                                           HttpServletRequest http, HttpServletResponse response) {
        AuthTokens tokens = auth.refresh(resolveRefreshToken(req, http), http);
        refreshCookie.issue(response, tokens.refreshToken());
        return ApiResponse.ok(tokens);
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the refresh token (request body or cookie) and clear the cookie",
            description = "No access token required, so signing out still works after it has expired. " +
                    "Only the refresh token presented is revoked.",
            security = {})
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest req,
                                       HttpServletRequest http, HttpServletResponse response) {
        // Cleared unconditionally: signing out must leave nothing in the jar even when the token is
        // already expired, already revoked or simply absent, so logout stays idempotent (204).
        String token = bodyToken(req);
        if (token == null) token = cookieToken(http);
        refreshCookie.clear(response);
        if (token != null) auth.logout(token);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @Operation(summary = "Get the currently authenticated user", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<UserDto> me(@CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(auth.me(me.userId()));
    }

    @PutMapping("/me")
    @Operation(summary = "Update my profile (name / phone / locale)", security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<UserDto> updateMe(
            @Valid @RequestBody com.eduplatform.eduplatform_backend.identity.web.dto.ProfileUpdateRequest req,
            @CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(auth.updateProfile(me.userId(), req));
    }

    // ---------------------------------------------------------------------
    // Admin self-registration: first-admin bootstrap only. AdminSignupService
    // refuses both steps (403 ADMIN_REGISTER_CLOSED) unless
    // app.security.admin-self-register-enabled=true AND no ADMIN/SUPER_ADMIN
    // exists yet.
    // ---------------------------------------------------------------------

    @PostMapping("/admin/register/start")
    @Operation(
            summary = "Begin admin self-registration; sends an OTP to the supplied email",
            description = "Bootstraps the first administrator. Allowed only while " +
                    "app.security.admin-self-register-enabled=true and no ADMIN or SUPER_ADMIN exists; " +
                    "otherwise 403 ADMIN_REGISTER_CLOSED. Submits admin details; an OTP is generated " +
                    "and (in production) emailed. Submit it to /admin/register/verify within 10 minutes.",
            security = {})
    public ResponseEntity<ApiResponse<AdminRegisterStartResponse>> adminStart(
            @Valid @RequestBody AdminRegisterStartRequest req,
            HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(adminSignup.start(req, http)));
    }

    @PostMapping("/admin/register/verify")
    @Operation(
            summary = "Verify the OTP and create the admin account",
            description = "On success creates a user with the ADMIN role and returns access + refresh tokens. " +
                    "Re-checks the same conditions as /start, so an OTP issued while bootstrap was open " +
                    "cannot create an admin once it has closed.",
            security = {})
    public ResponseEntity<ApiResponse<AuthTokens>> adminVerify(
            @Valid @RequestBody AdminRegisterVerifyRequest req,
            HttpServletRequest http,
            HttpServletResponse response) {
        AuthTokens tokens = adminSignup.verify(req, http);
        refreshCookie.issue(response, tokens.refreshToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(tokens));
    }

    // ---------------------------------------------------------------------
    // Refresh-token plumbing shared by /refresh and /logout.
    // ---------------------------------------------------------------------

    /**
     * Body first, cookie second. The public site's BFF holds the token server-side and posts it
     * explicitly; the admin portal posts nothing and relies on the httpOnly cookie, so neither
     * client had to change shape for the other's sake.
     */
    private String resolveRefreshToken(RefreshRequest req, HttpServletRequest http) {
        String token = bodyToken(req);
        if (token == null) token = cookieToken(http);
        if (token == null) {
            throw Errors.unauthorized("REFRESH_TOKEN_MISSING",
                    "No refresh token in the request body or session cookie");
        }
        return token;
    }

    /**
     * The portal's refresh cookie, honoured only on a request that comes from the portal itself.
     *
     * <p>The cookie is the dashboard's, but a browser attaches it to any request to this path that
     * CORS lets through — and CORS allows credentials for the public site too, which is same-site
     * with the dashboard. So without this check a script running on the public site could
     * refresh a signed-in administrator's session and read the fresh tokens out of the response.
     *
     * <p>A browser always sends Origin on these POSTs, and it must name the host the request was
     * sent to — the dashboard reaches the API through its own nginx at the same address — or the
     * configured portal URL, for a deployment where the dashboard calls the API on another host.
     * Hosts are compared without ports, since a cookie is not port-specific either (the local
     * stack serves the dashboard and the API from localhost on different ports). Where only
     * Sec-Fetch-Site is sent it must say same-origin. A request with neither did not come from a
     * browser page, and whoever sent it already holds the cookie's value. The public site's BFF
     * sends its token in the body and never reaches this.
     */
    private String cookieToken(HttpServletRequest http) {
        String token = refreshCookie.read(http);
        if (token == null) return null;
        String origin = http.getHeader(HttpHeaders.ORIGIN);
        String fetchSite = http.getHeader("Sec-Fetch-Site");
        boolean allowed = origin != null
                ? originOf(origin).equals(portalOrigin) || hostOf(origin).equals(requestHost(http))
                : fetchSite == null || "same-origin".equalsIgnoreCase(fetchSite);
        if (!allowed) {
            throw Errors.forbidden("REFRESH_ORIGIN_FORBIDDEN",
                    "The session cookie can only be used from the dashboard");
        }
        return token;
    }

    /** scheme://host[:port], lower-cased, without a path or trailing slash. */
    private static String originOf(String url) {
        String trimmed = url == null ? "" : url.trim().toLowerCase(java.util.Locale.ROOT);
        int schemeEnd = trimmed.indexOf("://");
        int pathStart = schemeEnd < 0 ? -1 : trimmed.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? trimmed : trimmed.substring(0, pathStart);
    }

    /** The host of an origin, without scheme or port; "" when there is none ("null", say). */
    private static String hostOf(String origin) {
        String o = originOf(origin);
        int schemeEnd = o.indexOf("://");
        if (schemeEnd < 0) return "";
        String hostPort = o.substring(schemeEnd + 3);
        if (hostPort.startsWith("[")) {   // an IPv6 literal
            int close = hostPort.indexOf(']');
            return close < 0 ? hostPort : hostPort.substring(0, close + 1);
        }
        int colon = hostPort.indexOf(':');
        return colon < 0 ? hostPort : hostPort.substring(0, colon);
    }

    /** The host the request was addressed to, as the Host header (kept by the proxies) names it. */
    private static String requestHost(HttpServletRequest http) {
        String host = http.getHeader(HttpHeaders.HOST);
        return host == null ? "" : hostOf("http://" + host);
    }

    private static String bodyToken(RefreshRequest req) {
        if (req == null || req.refreshToken() == null || req.refreshToken().isBlank()) return null;
        return req.refreshToken();
    }
}
