package com.eduplatform.eduplatform_backend.identity.service;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import com.eduplatform.eduplatform_backend.common.enums.TokenRevokeReason;
import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.common.error.AppException;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.enrollment.service.EnrollmentService;
import com.eduplatform.eduplatform_backend.identity.domain.Role;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.domain.UserRole;
import com.eduplatform.eduplatform_backend.identity.domain.UserRoleId;
import com.eduplatform.eduplatform_backend.identity.repo.RoleRepository;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import com.eduplatform.eduplatform_backend.identity.repo.UserRoleRepository;
import com.eduplatform.eduplatform_backend.identity.web.dto.AdminUserCreateRequest;
import com.eduplatform.eduplatform_backend.identity.web.dto.AdminUserDto;
import com.eduplatform.eduplatform_backend.identity.web.dto.AdminUserUpdateRequest;
import com.eduplatform.eduplatform_backend.review.service.ReviewService;
import com.eduplatform.eduplatform_backend.tutor.service.TutorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin user management — backs the dashboard Users page. All operations
 * require the {@code user:manage} authority (enforced at the controller).
 *
 * <p>{@code user:manage} is held by ADMIN as well as SUPER_ADMIN, so the privileged tier is
 * guarded here. Another administrator's account — ADMIN or SUPER_ADMIN — may be changed only by
 * a SUPER_ADMIN, and only a SUPER_ADMIN may grant or revoke SUPER_ADMIN
 * ({@code ROLE_ESCALATION_FORBIDDEN}); otherwise one ADMIN could reset another's password and
 * sign in as them. Nobody may change their own roles, disable or delete themselves
 * ({@code SELF_*_FORBIDDEN}), and the last active SUPER_ADMIN cannot be removed by any route
 * ({@code LAST_SUPER_ADMIN}), because nobody would be left to manage the others.
 *
 * <p>Every change that takes something away from an account — its status, its roles, its
 * password or the account itself — also ends what it was signed in with: the refresh tokens are
 * revoked and the access tokens made stale (see {@link UserSessionState}). A disabled account
 * used to stay signed in indefinitely, because nothing but a password reset revoked anything.
 *
 * <p>An administrator's own sign-in credentials are not this page's to change without the
 * current password (see {@link #update}): the Users page is reached with an access token alone,
 * and a borrowed or copied one used to be enough to set a new email and password and keep the
 * account for good.
 */
@Service
public class UserAdminService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final RefreshTokenService refreshTokens;
    private final UserSessionState sessions;
    private final TutorService tutors;
    private final EnrollmentService enrollments;
    private final ReviewService reviews;
    private final AuthService auth;
    private final LoginSecurityService loginSecurity;

    public UserAdminService(UserRepository users, RoleRepository roles, UserRoleRepository userRoles,
                            PasswordEncoder encoder, AuditService audit, RefreshTokenService refreshTokens,
                            UserSessionState sessions, TutorService tutors, EnrollmentService enrollments,
                            ReviewService reviews, AuthService auth, LoginSecurityService loginSecurity) {
        this.users = users;
        this.roles = roles;
        this.userRoles = userRoles;
        this.encoder = encoder;
        this.audit = audit;
        this.refreshTokens = refreshTokens;
        this.sessions = sessions;
        this.tutors = tutors;
        this.enrollments = enrollments;
        this.reviews = reviews;
        this.auth = auth;
        this.loginSecurity = loginSecurity;
    }

    /**
     * @param lockedOnly the dashboard's LOCKED filter: accounts in a temporary lockout, which is a
     *                   timestamp rather than a status and so cannot be asked for with {@code status}
     */
    @Transactional(readOnly = true)
    public Page<AdminUserDto> list(String search, RoleCode role, UserStatus status, boolean lockedOnly,
                                   Pageable pageable) {
        String s = (search == null || search.isBlank()) ? null : search.trim();
        return users.searchForAdmin(s, status, role, lockedOnly, Instant.now(), pageable).map(this::toDto);
    }

    @Transactional
    public AdminUserDto create(AdminUserCreateRequest req, UUID callerId) {
        if (req.roles().contains(RoleCode.SUPER_ADMIN) && !isSuperAdmin(callerId)) {
            throw escalationForbidden();
        }
        if (users.existsByEmailIgnoreCase(req.email())) {
            throw Errors.conflict("EMAIL_ALREADY_REGISTERED", "An account with this email already exists");
        }
        String[] name = splitName(req.fullName());
        User u = User.builder()
                .email(req.email())
                .phone(blankToNull(req.phone()))
                .passwordHash(hashOrNull(req.password()))
                .firstName(name[0])
                .lastName(name[1])
                .status(UserStatus.ACTIVE)
                .locale("en")
                .build();
        u.setId(UUID.randomUUID());
        // Roles go on BEFORE save(): the hand-assigned id makes save() a merge that returns a
        // managed copy, so links added to `u` afterwards would never be written. The returned
        // copy is what we keep — it carries the persisted links and the audited createdAt.
        applyRoles(u, req.roles());
        u = users.save(u);
        if (req.roles().contains(RoleCode.TUTOR)) {
            tutors.ensureApprovedProfile(u, callerId);
        }
        audit.record(AuditService.Actions.CREATE, "USER", u.getId(), null,
                AuditService.snapshot("email", u.getEmail(), "roles", sortedNames(req.roles())));
        return toDto(u);
    }

    /**
     * Applies an edit from the Users page. For the caller's own account, a new password is refused
     * outright — Settings changes it, asking for the current one and keeping the session — and a
     * new email needs {@code currentPassword}, checked and throttled like a sign-in. Without this
     * the self rules covered roles, status and deletion but not the credentials themselves.
     */
    @Transactional
    public AdminUserDto update(UUID id, AdminUserUpdateRequest req, UUID callerId, HttpServletRequest http) {
        User u = require(id);
        requireMayManage(u, callerId);
        boolean self = u.getId().equals(callerId);
        boolean emailChange = req.email() != null && !req.email().isBlank()
                && !req.email().equalsIgnoreCase(u.getEmail());
        if (self && req.password() != null && !req.password().isBlank()) {
            throw Errors.forbidden("SELF_PASSWORD_CHANGE_FORBIDDEN",
                    "Change your own password under Settings, which asks for the current one");
        }
        if (self && emailChange) {
            if (req.currentPassword() == null || req.currentPassword().isBlank()) {
                throw Errors.forbidden("CURRENT_PASSWORD_REQUIRED",
                        "Enter your current password to change your own email address");
            }
            auth.requireCurrentPassword(u, req.currentPassword(), http, "admin-self-email-change");
        }
        // An empty set has always meant "leave roles unchanged", and the dashboard resends the
        // current set on every edit, so only a set that actually differs counts as a change.
        Set<RoleCode> currentRoles = roleCodes(u);
        boolean rolesChange = req.roles() != null && !req.roles().isEmpty()
                && !currentRoles.equals(new HashSet<>(req.roles()));
        if (rolesChange) {
            requireMayChangeRoles(u, req.roles(), callerId);
        }
        // LOCKED is what the list shows for a lockout, not a status anyone can set; a form that
        // sends back what it was shown means "no change".
        UserStatus newStatus = req.status() == null || req.status().isBlank()
                || "LOCKED".equalsIgnoreCase(req.status().trim())
                ? null : fromFrontendStatus(req.status());
        boolean statusChange = newStatus != null && newStatus != u.getStatus();
        if (statusChange) {
            requireNotSelf(u, callerId, "SELF_STATUS_CHANGE_FORBIDDEN",
                    "You cannot disable or enable your own account; ask another administrator");
        }
        boolean losesSuperAdmin = currentRoles.contains(RoleCode.SUPER_ADMIN)
                && ((rolesChange && !req.roles().contains(RoleCode.SUPER_ADMIN))
                    || (statusChange && newStatus != UserStatus.ACTIVE));
        if (losesSuperAdmin) {
            requireAnotherActiveSuperAdmin(u);
        }

        if (emailChange) {
            if (users.existsByEmailIgnoreCase(req.email())) {
                throw Errors.conflict("EMAIL_ALREADY_REGISTERED", "An account with this email already exists");
            }
            u.setEmail(req.email());
        }
        if (req.fullName() != null && !req.fullName().isBlank()) {
            String[] name = splitName(req.fullName());
            u.setFirstName(name[0]);
            u.setLastName(name[1]);
        }
        if (req.phone() != null) {
            u.setPhone(blankToNull(req.phone()));
        }
        boolean passwordChanged = req.password() != null && !req.password().isBlank();
        if (passwordChanged) {
            u.setPasswordHash(encoder.encode(req.password()));
        }
        if (newStatus != null) {
            applyStatus(u, newStatus);
        }
        if (rolesChange) {
            applyRoles(u, req.roles());
            if (req.roles().contains(RoleCode.TUTOR) && !currentRoles.contains(RoleCode.TUTOR)) {
                tutors.ensureApprovedProfile(u, callerId);
            }
        }

        // A new password or a lost status ends every session; a role change only has to make
        // the access tokens stale, since the next refresh re-reads the roles anyway, and so does
        // a new email, which every access token carries.
        if (passwordChanged || (statusChange && newStatus != UserStatus.ACTIVE)) {
            refreshTokens.revokeAllForUser(u.getId(), TokenRevokeReason.ADMIN);
        }
        if (passwordChanged || statusChange || rolesChange || emailChange) {
            sessions.revokeAccessTokens(u);
        }

        Map<String, Object> after = AuditService.snapshot("email", u.getEmail());
        if (passwordChanged) after.put("passwordChanged", true);
        if (statusChange) after.put("status", u.getStatus().name());
        if (rolesChange) after.put("roles", sortedNames(req.roles()));
        audit.record(AuditService.Actions.UPDATE, "USER", u.getId(),
                rolesChange ? AuditService.snapshot("roles", sortedNames(currentRoles)) : null, after);
        return toDto(u);
    }

    @Transactional
    public AdminUserDto setStatus(UUID id, String frontendStatus, UUID callerId) {
        User u = require(id);
        requireMayManage(u, callerId);
        requireNotSelf(u, callerId, "SELF_STATUS_CHANGE_FORBIDDEN",
                "You cannot disable or enable your own account; ask another administrator");
        UserStatus newStatus = fromFrontendStatus(frontendStatus);
        UserStatus before = u.getStatus();
        if (newStatus != UserStatus.ACTIVE && roleCodes(u).contains(RoleCode.SUPER_ADMIN)) {
            requireAnotherActiveSuperAdmin(u);
        }
        applyStatus(u, newStatus);
        if (newStatus != UserStatus.ACTIVE) {
            refreshTokens.revokeAllForUser(u.getId(), TokenRevokeReason.ADMIN);
        }
        if (newStatus != before) {
            sessions.revokeAccessTokens(u);
        }
        audit.record(AuditService.Actions.UPDATE, "USER", u.getId(),
                AuditService.snapshot("status", before.name()),
                AuditService.snapshot("status", u.getStatus().name()));
        return toDto(u);
    }

    /**
     * Soft-deletes an account. An expert whose profile is still named on a course or a room booking
     * is refused with 409 by {@link TutorService#retireProfileOf}: the soft-deleted account would
     * leave those rows pointing at a user every read then fails to load, which turned the course
     * lists, the catalogue and the Tutors page into 500s. Their courses have to be reassigned
     * first, or the account disabled instead, which keeps it but signs it out.
     *
     * <p>What the account held as a participant goes with it: its places are cancelled and the
     * courses recounted, so an in-person seat is free again, and its reviews are retired and the
     * ratings recomputed. Both used to outlive the account — a seat that nobody could see or free,
     * a rating that counted a review nobody could see or hide.
     */
    @Transactional
    public void delete(UUID id, UUID callerId) {
        User u = require(id);
        requireMayManage(u, callerId);
        requireNotSelf(u, callerId, "SELF_DELETE_FORBIDDEN",
                "You cannot delete your own account; ask another administrator");
        if (roleCodes(u).contains(RoleCode.SUPER_ADMIN)) {
            requireAnotherActiveSuperAdmin(u);
        }
        tutors.retireProfileOf(u.getId());
        // Reviews first: recomputing a rating loads and saves the course, and the place recount
        // after it is a plain UPDATE that a later save of a stale copy would undo.
        int reviewsRetired = reviews.retireReviewsOf(u.getId());
        int placesCancelled = enrollments.releasePlacesOf(u.getId());
        users.delete(u); // soft-delete via @SQLDelete on User
        // The dashboard's delete dialog promises the sessions are invalidated, and until this
        // they were not: the refresh token kept rotating for a user no read could load.
        refreshTokens.revokeAllForUser(id, TokenRevokeReason.ADMIN);
        sessions.invalidateAfterCommit(id);
        audit.record(AuditService.Actions.DELETE, "USER", id, null,
                AuditService.snapshot("email", u.getEmail(), "enrolmentsCancelled", placesCancelled,
                        "reviewsRetired", reviewsRetired));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private User require(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));
    }

    /**
     * Another administrator's account — ADMIN or SUPER_ADMIN — may only be changed by a
     * SUPER_ADMIN. Without this an ADMIN could reset a fellow administrator's email or password
     * and sign in as them, or disable or delete them. An administrator's own account is the
     * self rules' business (see {@link #requireNotSelf} and {@link #requireMayChangeRoles}).
     */
    private void requireMayManage(User target, UUID callerId) {
        if (target.getId().equals(callerId)) return;
        Set<RoleCode> targetRoles = roleCodes(target);
        boolean privileged = targetRoles.contains(RoleCode.SUPER_ADMIN) || targetRoles.contains(RoleCode.ADMIN);
        if (privileged && !isSuperAdmin(callerId)) {
            throw Errors.forbidden("ROLE_ESCALATION_FORBIDDEN",
                    "Only a SUPER_ADMIN can change another administrator's account");
        }
    }

    private static void requireNotSelf(User target, UUID callerId, String code, String message) {
        if (target.getId().equals(callerId)) {
            throw Errors.forbidden(code, message);
        }
    }

    /**
     * The privilege check runs before the self rule, so an ADMIN promoting itself is reported
     * as the escalation it is; the self rule then also stops a SUPER_ADMIN demoting itself.
     */
    private void requireMayChangeRoles(User target, Set<RoleCode> requested, UUID callerId) {
        boolean superAdminToggled =
                roleCodes(target).contains(RoleCode.SUPER_ADMIN) != requested.contains(RoleCode.SUPER_ADMIN);
        if (superAdminToggled && !isSuperAdmin(callerId)) {
            throw escalationForbidden();
        }
        if (target.getId().equals(callerId)) {
            throw Errors.forbidden("SELF_ROLE_CHANGE_FORBIDDEN",
                    "You cannot change your own roles; ask another administrator");
        }
    }

    /**
     * Refuses to take the last active SUPER_ADMIN away, whether by disabling, deleting or demoting
     * it. The self rules already stop a SUPER_ADMIN removing itself, so this is about two
     * SUPER_ADMINs removing each other at the same moment: the SUPER_ADMIN role row is locked
     * first, so the second request waits for the first to commit and then counts correctly.
     */
    private void requireAnotherActiveSuperAdmin(User target) {
        roles.findByCodeForUpdate(RoleCode.SUPER_ADMIN);
        if (target.getStatus() == UserStatus.ACTIVE
                && users.countActiveWithRoleExcluding(RoleCode.SUPER_ADMIN, target.getId()) == 0) {
            throw Errors.conflict("LAST_SUPER_ADMIN",
                    "This is the last active SUPER_ADMIN; grant SUPER_ADMIN to someone else first");
        }
    }

    /**
     * Read from the database rather than the caller's JWT: the token's role claim can be up to
     * one access-token lifetime stale, and a demoted SUPER_ADMIN must lose this power at once.
     */
    private boolean isSuperAdmin(UUID callerId) {
        return userRoles.findRoleCodesByUserId(callerId).contains(RoleCode.SUPER_ADMIN);
    }

    private static AppException escalationForbidden() {
        return Errors.forbidden("ROLE_ESCALATION_FORBIDDEN",
                "Only a SUPER_ADMIN can grant or revoke SUPER_ADMIN or change a SUPER_ADMIN account");
    }

    private static Set<RoleCode> roleCodes(User u) {
        return u.getUserRoles().stream()
                .map(ur -> ur.getRole().getCode())
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static java.util.List<String> sortedNames(Set<RoleCode> codes) {
        return codes.stream().map(Enum::name).sorted().toList();
    }

    /**
     * Setting an account ACTIVE also clears its failed-login count and every lockout on it, from
     * whichever client address. Re-enabling used to leave the count at five, so the very next
     * typo locked the account again.
     */
    private void applyStatus(User u, UserStatus status) {
        u.setStatus(status);
        if (status == UserStatus.ACTIVE) {
            loginSecurity.releaseAll(u);
        }
    }

    /** Replaces the user's role set with exactly {@code codes}, diffing to avoid PK churn. */
    private void applyRoles(User u, Set<RoleCode> codes) {
        Map<UUID, Role> resolved = new HashMap<>();
        for (RoleCode code : codes) {
            Role r = roles.findByCode(code)
                    .orElseThrow(() -> Errors.badRequest("ROLE_NOT_FOUND", "Unknown role: " + code));
            resolved.put(r.getId(), r);
        }
        // Drop links no longer wanted.
        u.getUserRoles().removeIf(ur -> !resolved.containsKey(ur.getRole().getId()));
        // Add the ones not already present.
        Set<UUID> existing = u.getUserRoles().stream()
                .map(ur -> ur.getRole().getId())
                .collect(Collectors.toCollection(HashSet::new));
        for (Map.Entry<UUID, Role> e : resolved.entrySet()) {
            if (!existing.contains(e.getKey())) {
                Role r = e.getValue();
                u.getUserRoles().add(UserRole.builder()
                        .id(new UserRoleId(u.getId(), r.getId()))
                        .user(u)
                        .role(r)
                        .grantedAt(Instant.now())
                        .build());
            }
        }
    }

    private AdminUserDto toDto(User u) {
        Set<String> roleCodes = u.getUserRoles().stream()
                .map(ur -> ur.getRole().getCode().name())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String last = u.getLastName() == null ? "" : u.getLastName();
        String fullName = (u.getFirstName() + " " + last).trim();
        Instant lockedUntil = LoginSecurityService.activeLock(u, Instant.now());
        return new AdminUserDto(
                u.getId(), u.getEmail(), fullName, u.getPhone(),
                roleCodes, toFrontendStatus(u.getStatus(), lockedUntil),
                u.getCreatedAt(), u.getLastLoginAt(), lockedUntil);
    }

    private static String[] splitName(String fullName) {
        String fn = fullName.trim();
        int sp = fn.indexOf(' ');
        if (sp < 0) return new String[]{fn, ""};
        return new String[]{fn.substring(0, sp), fn.substring(sp + 1).trim()};
    }

    private String hashOrNull(String raw) {
        return (raw == null || raw.isBlank()) ? null : encoder.encode(raw);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Backend status → dashboard vocabulary. LOCKED is its own value — it used to fold into
     * DISABLED, so the dashboard's Unlock action could never appear — and it covers the temporary
     * lockout, which is a timestamp on an otherwise ACTIVE account.
     */
    private static String toFrontendStatus(UserStatus s, Instant lockedUntil) {
        return switch (s) {
            case ACTIVE -> lockedUntil != null ? "LOCKED" : "ACTIVE";
            case LOCKED -> "LOCKED";
            default -> "DISABLED";
        };
    }

    /** Dashboard vocabulary → backend status. */
    private static UserStatus fromFrontendStatus(String s) {
        return switch (s.trim().toUpperCase()) {
            case "ACTIVE" -> UserStatus.ACTIVE;
            case "DISABLED" -> UserStatus.SUSPENDED;
            default -> throw Errors.badRequest("INVALID_STATUS", "Unsupported status: " + s);
        };
    }
}
