package com.eduplatform.eduplatform_backend.identity.service;

import com.eduplatform.eduplatform_backend.audit.service.SecurityEventRecorder;
import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import com.eduplatform.eduplatform_backend.common.enums.TokenRevokeReason;
import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public AuthService(UserRepository users, RoleRepository roles, PermissionRepository perms,
                       PasswordEncoder encoder, JwtService jwt, RefreshTokenService refreshService,
                       LoginSecurityService loginSecurity, SecurityEventRecorder securityEvents) {
        this.users = users;
        this.roles = roles;
        this.perms = perms;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshService = refreshService;
        this.loginSecurity = loginSecurity;
        this.securityEvents = securityEvents;
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

    @Transactional
    public AuthTokens login(LoginRequest req, HttpServletRequest http) {
        User user = users.findByEmailIgnoreCase(req.email())
                .orElseThrow(() -> Errors.unauthorized("INVALID_CREDENTIALS", "Invalid email or password"));

        if (user.getPasswordHash() == null) {
            throw Errors.unauthorized("PASSWORD_LOGIN_DISABLED",
                    "This account uses social login; please sign in with the matching provider");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw Errors.forbidden("ACCOUNT_NOT_ACTIVE", "Account is " + user.getStatus());
        }
        if (!encoder.matches(req.password(), user.getPasswordHash())) {
            // Bookkeeping + audit must commit even though this attempt rolls back (REQUIRES_NEW).
            boolean nowLocked = loginSecurity.registerFailedAttempt(user.getId());
            securityEvents.record(user.getId(), SecurityEventRecorder.FAILED_LOGIN, http,
                    Map.of("email", user.getEmail()));
            if (nowLocked) {
                securityEvents.record(user.getId(), SecurityEventRecorder.LOCKOUT, http,
                        Map.of("email", user.getEmail()));
            }
            throw Errors.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
        }
        users.markLoginSuccess(user.getId(), Instant.now());
        return issueTokens(user, http);
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

    public void logout(String refreshToken) {
        refreshService.revoke(refreshToken, TokenRevokeReason.LOGOUT);
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
        if (req.firstName() != null && !req.firstName().isBlank()) u.setFirstName(req.firstName().trim());
        if (req.lastName() != null && !req.lastName().isBlank()) u.setLastName(req.lastName().trim());
        if (req.phone() != null) u.setPhone(req.phone().isBlank() ? null : req.phone().trim());
        if (req.locale() != null && !req.locale().isBlank()) u.setLocale(req.locale().trim());
        users.save(u);
        return toDto(u);
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
        JwtService.IssuedToken access = jwt.issueAccess(user.getId(), user.getEmail(), roleCodes, permCodes);
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
