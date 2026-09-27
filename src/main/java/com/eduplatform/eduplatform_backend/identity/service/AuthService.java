package com.eduplatform.eduplatform_backend.identity.service;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.audit.service.HttpMeta;
import com.eduplatform.eduplatform_backend.audit.service.SecurityEventRecorder;
import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import com.eduplatform.eduplatform_backend.common.enums.TokenRevokeReason;
import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.common.error.AppException;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.JwtService;
import com.eduplatform.eduplatform_backend.identity.domain.Role;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.domain.UserRole;
import com.eduplatform.eduplatform_backend.identity.domain.UserRoleId;
import com.eduplatform.eduplatform_backend.identity.repo.PermissionRepository;
import com.eduplatform.eduplatform_backend.identity.repo.RoleRepository;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import com.eduplatform.eduplatform_backend.identity.web.dto.AuthTokens;
import com.eduplatform.eduplatform_backend.identity.web.dto.LoginRequest;
import com.eduplatform.eduplatform_backend.identity.web.dto.RegisterRequest;
import com.eduplatform.eduplatform_backend.identity.web.dto.UserDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PermissionRepository perms;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refreshService;
    private final LoginSecurityService loginSecurity;
    private final SecurityEventRecorder securityEvents;
    private final AuditService audit;
    private final UserSessionState sessions;

    /**
     * A bcrypt hash of nothing anyone knows, compared against when there is no real hash to
     * compare against, so an unknown address takes as long to refuse as a wrong password.
     */
    private final String dummyHash;

    public AuthService(UserRepository users, RoleRepository roles, PermissionRepository perms,
                       PasswordEncoder encoder, JwtService jwt, RefreshTokenService refreshService,
                       LoginSecurityService loginSecurity, SecurityEventRecorder securityEvents,
                       AuditService audit, UserSessionState sessions) {
        this.users = users;
        this.roles = roles;
        this.perms = perms;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshService = refreshService;
        this.loginSecurity = loginSecurity;
        this.securityEvents = securityEvents;
        this.audit = audit;
        this.sessions = sessions;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthTokens register(RegisterRequest req, HttpServletRequest http) {
        User u = createUserWithUserRole(req.email(), req.password(), req.firstName(),
                req.lastName(), req.phone(), req.locale());
        return issueTokens(u, http);
    }

    private User createUserWithUserRole(String email, String rawPassword, String firstName,
                                        String lastName, String phone, String locale) {
        return createUserWithUserRolePreHashed(email, encoder.encode(rawPassword),
                firstName, lastName, phone, locale);
    }

    /**
     * Creates an ACTIVE user with the USER role from an already-bcrypt-hashed password.
     * Used by self-registration and by the OTP signup flows that hashed the password at the
     * "start" step. Returns the managed instance, so callers see the persisted role link.
     */
    @Transactional
    public User createUserWithUserRolePreHashed(String email, String passwordHash, String firstName,
                                                String lastName, String phone, String locale) {
        if (users.existsByEmailIgnoreCase(email)) {
            throw Errors.conflict("EMAIL_ALREADY_REGISTERED", "An account with this email already exists");
        }
        Role userRole = roles.findByCode(RoleCode.USER)
                .orElseThrow(() -> new IllegalStateException("Role USER missing — V2 seed migration did not run"));

        User u = User.builder()
                .email(email)
                .phone(phone)
                .passwordHash(passwordHash)
                .firstName(firstName)
                .lastName(lastName)
                .status(UserStatus.ACTIVE)
                .locale(locale == null ? "en" : locale)
                .build();
        u.setId(UUID.randomUUID());

        // The link must be on the collection BEFORE save(). The hand-assigned id makes save()
        // a merge that returns a managed copy; a link added to `u` afterwards lands on the
        // detached original and is never written, leaving the account with no role at all.
        // cascade=ALL on User.userRoles carries the link through the merge.
        u.getUserRoles().add(UserRole.builder()
                .id(new UserRoleId(u.getId(), userRole.getId()))
                .user(u)
                .role(userRole)
                .grantedAt(Instant.now())
                .build());
        return users.save(u);
    }

    /**
     * Password sign-in. The order of the checks is the point:
     *
     * <ol>
     *   <li>A temporary lockout is answered before the password is looked at, so a guesser learns
     *       nothing from the attempts it makes while locked out. It is kept per address typed and
     *       per client address (see LoginSecurityService): it shuts out the client the wrong
     *       passwords came from, not the account's owner signing in from elsewhere, and an
     *       unknown address locks exactly as a registered one does.</li>
     *   <li>An unknown address costs the same bcrypt comparison as a known one, is counted the
     *       same way and gets the same answer, so neither timing nor the reply says whether an
     *       account exists.</li>
     *   <li>Only a correct password reaches the account's status. The status used to be checked
     *       first, so any wrong password for a disabled or locked account answered "Account is
     *       SUSPENDED", which told anyone which addresses exist and what state they are in.</li>
     * </ol>
     *
     * <p>An account with no password (social sign-in only) is a wrong password like any other:
     * telling the caller to use Google instead would confirm the address is registered.
     */
    @Transactional
    public AuthTokens login(LoginRequest req, HttpServletRequest http) {
        User user = users.findByEmailIgnoreCase(req.email()).orElse(null);
        Instant now = Instant.now();
        String clientIp = HttpMeta.clientIp(http);
        Duration locked = loginSecurity.lockRemaining(req.email(), clientIp);
        if (locked != null) {
            throw temporarilyLocked(locked);
        }
        if (user == null) {
            encoder.matches(req.password(), dummyHash);
            loginSecurity.registerFailedAttempt(req.email(), null, clientIp);
            throw invalidCredentials();
        }
        boolean passwordMatches;
        if (user.getPasswordHash() == null) {
            encoder.matches(req.password(), dummyHash);   // same cost as a real check; never a match
            passwordMatches = false;
        } else {
            passwordMatches = encoder.matches(req.password(), user.getPasswordHash());
        }
        if (!passwordMatches) {
            // Bookkeeping + audit must commit even though this attempt rolls back (REQUIRES_NEW).
            boolean nowLocked = loginSecurity.registerFailedAttempt(req.email(), user.getId(), clientIp);
            securityEvents.record(user.getId(), SecurityEventRecorder.FAILED_LOGIN, http,
                    Map.of("email", user.getEmail()));
            if (nowLocked) {
                securityEvents.record(user.getId(), SecurityEventRecorder.LOCKOUT, http,
                        Map.of("email", user.getEmail()));
            }
            throw invalidCredentials();
        }
        if (!UserSessionState.isSignInStatus(user.getStatus())) {
            throw Errors.forbidden("ACCOUNT_NOT_ACTIVE", "This account is disabled");
        }
        users.markLoginSuccess(user.getId(), now);
        loginSecurity.clearAddress(req.email(), clientIp);
        if (user.getStatus() == UserStatus.LOCKED) {
            // LOCKED only ever meant "too many failed logins", which a correct password now ends.
            // Nothing sets it any more (V14 cleared the old rows); this covers a row restored
            // from a backup taken before that.
            users.releaseLegacyLock(user.getId());
        }
        audit.record(user.getId(), null, AuditService.Actions.LOGIN, "USER", user.getId(), null, null);
        return issueTokens(user, http);
    }

    private static AppException invalidCredentials() {
        return Errors.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
    }

    /**
     * 429 rather than 401 or 403: the request was fine and the account is fine, there have simply
     * been too many attempts, and Retry-After tells the sign-in form how long to wait.
     */
    private static AppException temporarilyLocked(Duration remaining) {
        long seconds = Math.max(1, remaining.toSeconds());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        long minutes = (seconds + 59) / 60;
        return new AppException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_TEMPORARILY_LOCKED",
                "Too many failed sign-in attempts. Try again in " + minutes
                        + (minutes == 1 ? " minute." : " minutes."), headers);
    }

    // Reuse detection revokes the family and then fails the request. That revocation is written in
    // this transaction, so it has to commit despite the exception — hence noRollbackFor. Doing it in
    // a nested transaction instead needed a second pooled connection per request, which let
    // concurrent replays exhaust the pool. See RefreshTokenService.ReuseDetected.
    @Transactional(noRollbackFor = RefreshTokenService.ReuseDetected.class)
    public AuthTokens refresh(String refreshToken, HttpServletRequest http) {
        RefreshTokenService.Rotated rotated = refreshService.rotate(refreshToken, http);
        AuthTokens base = buildAccessFor(rotated.user());
        return new AuthTokens(
                base.accessToken(), rotated.rawToken(), "Bearer",
                base.accessExpiresAt(), rotated.expiresAt(), base.user());
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshService.revoke(refreshToken, TokenRevokeReason.LOGOUT).ifPresent(userId ->
                audit.record(userId, null, AuditService.Actions.LOGOUT, "USER", userId, null, null));
    }

    @Transactional(readOnly = true)
    public UserDto me(UUID userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));
        return toDto(user);
    }

    @Transactional
    public UserDto updateProfile(UUID userId, com.eduplatform.eduplatform_backend.identity.web.dto.ProfileUpdateRequest req) {
        User u = users.findById(userId)
                .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));
        // Blank names and unknown locales are refused by ProfileUpdateRequest's validation; the
        // checks here only keep a blank from being stored if that validation is ever bypassed.
        if (req.firstName() != null && !req.firstName().isBlank()) u.setFirstName(req.firstName().trim());
        if (req.lastName() != null && !req.lastName().isBlank()) u.setLastName(req.lastName().trim());
        if (req.phone() != null) u.setPhone(req.phone().isBlank() ? null : req.phone().trim());
        if (req.locale() != null && !req.locale().isBlank()) u.setLocale(req.locale().trim().toLowerCase(java.util.Locale.ROOT));
        users.save(u);
        return toDto(u);
    }

    /**
     * A signed-in user changing their own password — until now the only way was the emailed
     * reset, which does not work while mail is off. The current password is required, so a
     * borrowed session cannot take the account over, and a wrong one counts towards the same
     * lockout as a wrong sign-in, so this cannot be used to guess it instead.
     *
     * <p>Every other session is signed out: their refresh tokens are revoked, and every access
     * token issued so far is made stale, which revoking the refresh tokens alone did not do — a
     * second session kept its API access for up to the rest of its 15-minute token. The session
     * making the change keeps its refresh token, so the client's next call is answered 401
     * TOKEN_STALE and its usual refresh-and-retry carries on with a fresh token.
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword,
                               String keepRefreshToken, HttpServletRequest http) {
        User user = users.findById(userId)
                .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));
        requireCurrentPassword(user, currentPassword, http, "password-change");
        user.setPasswordHash(encoder.encode(newPassword));
        user.setFailedLogins((short) 0);
        sessions.revokeAccessTokens(user);
        users.save(user);
        refreshService.revokeOtherSessions(userId, keepRefreshToken, TokenRevokeReason.ADMIN);
        securityEvents.record(userId, SecurityEventRecorder.PASSWORD_CHANGE, http, Map.of());
        audit.record(userId, null, AuditService.Actions.UPDATE, "USER", userId, null,
                AuditService.snapshot("passwordChanged", true));
    }

    /**
     * Refuses a change to the signed-in user's own credentials unless it carries their current
     * password: a session alone — a borrowed laptop, a copied 15-minute token — must not be enough
     * to take the account over. A wrong one counts towards the same lockout as a wrong sign-in,
     * so this is no side door for guessing it.
     *
     * @param via which path asked, for the security event
     */
    public void requireCurrentPassword(User user, String currentPassword, HttpServletRequest http, String via) {
        String clientIp = HttpMeta.clientIp(http);
        Duration locked = loginSecurity.lockRemaining(user.getEmail(), clientIp);
        if (locked != null) {
            throw temporarilyLocked(locked);
        }
        if (user.getPasswordHash() == null) {
            throw Errors.badRequest("PASSWORD_NOT_SET",
                    "This account signs in with a social provider and has no password to change");
        }
        if (currentPassword == null || !encoder.matches(currentPassword, user.getPasswordHash())) {
            loginSecurity.registerFailedAttempt(user.getEmail(), user.getId(), clientIp);
            securityEvents.record(user.getId(), SecurityEventRecorder.FAILED_LOGIN, http,
                    Map.of("email", user.getEmail(), "via", via));
            // 400, not 401: a 401 makes the dashboard refresh the session and retry.
            throw Errors.badRequest("INVALID_CURRENT_PASSWORD", "The current password is not correct");
        }
    }

    /** Used by OAuth flows after they materialise the {@link User}. */
    public AuthTokens issueTokens(User user, HttpServletRequest http) {
        AuthTokens base = buildAccessFor(user);
        RefreshTokenService.Rotated refresh = refreshService.issueNew(user, http);
        return new AuthTokens(
                base.accessToken(), refresh.rawToken(), "Bearer",
                base.accessExpiresAt(), refresh.expiresAt(), base.user());
    }

    private AuthTokens buildAccessFor(User user) {
        List<String> roleCodes = user.getUserRoles().stream()
                .map(ur -> ur.getRole().getCode().name())
                .toList();
        List<String> permCodes = perms.findPermissionCodesByUserId(user.getId()).stream().sorted().toList();
        JwtService.IssuedToken access = jwt.issueAccess(user.getId(), user.getEmail(), roleCodes, permCodes,
                user.getTokenVersion());
        return new AuthTokens(access.token(), null, "Bearer", access.expiresAt(), null,
                toDto(user, roleCodes, permCodes));
    }

    private UserDto toDto(User u) {
        List<String> roleCodes = u.getUserRoles().stream().map(ur -> ur.getRole().getCode().name()).toList();
        List<String> permCodes = perms.findPermissionCodesByUserId(u.getId()).stream().sorted().toList();
        return toDto(u, roleCodes, permCodes);
    }

    private UserDto toDto(User u, List<String> roleCodes, List<String> permCodes) {
        return new UserDto(
                u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getPhone(),
                u.getLocale(), u.getStatus(), u.getEmailVerifiedAt() != null,
                u.getLastLoginAt(),
                new HashSet<>(roleCodes), new HashSet<>(permCodes));
    }
}
