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

    public AuthController(AuthService auth, AdminSignupService adminSignup, TutorSignupService tutorSignup,
                          com.eduplatform.eduplatform_backend.identity.service.AccountRecoveryService recovery,
                          RefreshTokenCookie refreshCookie) {
        this.auth = auth;
        this.adminSignup = adminSignup;
        this.tutorSignup = tutorSignup;
        this.recovery = recovery;
        this.refreshCookie = refreshCookie;
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
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest req,
                                       HttpServletRequest http, HttpServletResponse response) {
        // Cleared unconditionally: signing out must leave nothing in the jar even when the token is
        // already expired, already revoked or simply absent, so logout stays idempotent (204).
        refreshCookie.clear(response);
        String token = bodyToken(req);
        if (token == null) token = refreshCookie.read(http);
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
    // Admin self-registration (bootstrap-mode, no auth required for v1).
    // Lock this down later by requiring an invite token from an existing
    // SUPER_ADMIN before allowing /start.
    // ---------------------------------------------------------------------

    @PostMapping("/admin/register/start")
    @Operation(
            summary = "Begin admin self-registration; sends an OTP to the supplied email",
            description = "Open endpoint for v1 bootstrap. Submits admin details; an OTP is generated " +
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
            description = "On success creates a user with the ADMIN role and returns access + refresh tokens.",
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
        if (token == null) token = refreshCookie.read(http);
        if (token == null) {
            throw Errors.unauthorized("REFRESH_TOKEN_MISSING",
                    "No refresh token in the request body or session cookie");
        }
        return token;
    }

    private static String bodyToken(RefreshRequest req) {
        if (req == null || req.refreshToken() == null || req.refreshToken().isBlank()) return null;
        return req.refreshToken();
    }
}
