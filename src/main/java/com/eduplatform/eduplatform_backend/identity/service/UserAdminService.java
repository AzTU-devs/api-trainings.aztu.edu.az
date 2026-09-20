package com.eduplatform.eduplatform_backend.identity.service;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.common.error.AppException;
import com.eduplatform.eduplatform_backend.common.error.Errors;
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
 * <p>{@code user:manage} is held by ADMIN as well as SUPER_ADMIN, so the SUPER_ADMIN tier is
 * guarded here: only a SUPER_ADMIN may grant or revoke SUPER_ADMIN, or change a SUPER_ADMIN's
 * account at all ({@code ROLE_ESCALATION_FORBIDDEN}), and nobody may change their own roles
 * ({@code SELF_ROLE_CHANGE_FORBIDDEN}).
 */
@Service
public class UserAdminService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserAdminService(UserRepository users, RoleRepository roles, UserRoleRepository userRoles,
                            PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.roles = roles;
        this.userRoles = userRoles;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<AdminUserDto> list(String search, RoleCode role, UserStatus status, Pageable pageable) {
        String s = (search == null || search.isBlank()) ? null : search.trim();
        return users.searchForAdmin(s, status, role, pageable).map(this::toDto);
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
        audit.record(AuditService.Actions.CREATE, "USER", u.getId(), null,
                AuditService.snapshot("email", u.getEmail()));
        return toDto(u);
    }

    @Transactional
    public AdminUserDto update(UUID id, AdminUserUpdateRequest req, UUID callerId) {
        User u = require(id);
        requireMayManage(u, callerId);
        // An empty set has always meant "leave roles unchanged", and the dashboard resends the
        // current set on every edit, so only a set that actually differs counts as a change.
        boolean rolesChange = req.roles() != null && !req.roles().isEmpty()
                && !roleCodes(u).equals(new HashSet<>(req.roles()));
        if (rolesChange) {
            requireMayChangeRoles(u, req.roles(), callerId);
        }

        if (req.email() != null && !req.email().isBlank()
                && !req.email().equalsIgnoreCase(u.getEmail())) {
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
        if (req.password() != null && !req.password().isBlank()) {
            u.setPasswordHash(encoder.encode(req.password()));
        }
        if (req.status() != null && !req.status().isBlank()) {
            u.setStatus(fromFrontendStatus(req.status()));
        }
        if (rolesChange) {
            applyRoles(u, req.roles());
        }
        audit.record(AuditService.Actions.UPDATE, "USER", u.getId(), null,
                AuditService.snapshot("email", u.getEmail()));
        return toDto(u);
    }

    @Transactional
    public AdminUserDto setStatus(UUID id, String frontendStatus, UUID callerId) {
        User u = require(id);
        requireMayManage(u, callerId);
        u.setStatus(fromFrontendStatus(frontendStatus));
        audit.record(AuditService.Actions.UPDATE, "USER", u.getId(), null,
                AuditService.snapshot("status", u.getStatus().name()));
        return toDto(u);
    }

    @Transactional
    public void delete(UUID id, UUID callerId) {
        User u = require(id);
        requireMayManage(u, callerId);
        users.delete(u); // soft-delete via @SQLDelete on User
        audit.record(AuditService.Actions.DELETE, "USER", id, null,
                AuditService.snapshot("email", u.getEmail()));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private User require(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));
    }

    /**
     * A SUPER_ADMIN account may only be changed by a SUPER_ADMIN. Without this an ADMIN could
     * reset a SUPER_ADMIN's email or password and sign in as them, or disable or delete them,
     * which reaches the same place as granting or revoking the role directly.
     */
    private void requireMayManage(User target, UUID callerId) {
        if (roleCodes(target).contains(RoleCode.SUPER_ADMIN) && !isSuperAdmin(callerId)) {
            throw escalationForbidden();
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
        return new AdminUserDto(
                u.getId(), u.getEmail(), fullName, u.getPhone(),
                roleCodes, toFrontendStatus(u.getStatus()),
                u.getCreatedAt(), u.getLastLoginAt());
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

    /** Backend status → dashboard vocabulary. */
    private static String toFrontendStatus(UserStatus s) {
        return s == UserStatus.ACTIVE ? "ACTIVE" : "DISABLED";
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
