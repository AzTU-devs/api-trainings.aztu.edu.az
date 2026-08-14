package com.eduplatform.eduplatform_backend.identity.service;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.identity.domain.Role;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.domain.UserRole;
import com.eduplatform.eduplatform_backend.identity.domain.UserRoleId;
import com.eduplatform.eduplatform_backend.identity.repo.RoleRepository;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
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
 */
@Service
public class UserAdminService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserAdminService(UserRepository users, RoleRepository roles, PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<AdminUserDto> list(String search, RoleCode role, UserStatus status, Pageable pageable) {
        String s = (search == null || search.isBlank()) ? null : search.trim();
        return users.searchForAdmin(s, status, role, pageable).map(this::toDto);
    }

    @Transactional
    public AdminUserDto create(AdminUserCreateRequest req) {
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
        users.save(u);
        applyRoles(u, req.roles());
        audit.record(AuditService.Actions.CREATE, "USER", u.getId(), null,
                AuditService.snapshot("email", u.getEmail()));
        return toDto(u);
    }

    @Transactional
    public AdminUserDto update(UUID id, AdminUserUpdateRequest req) {
        User u = require(id);

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
        if (req.roles() != null && !req.roles().isEmpty()) {
            applyRoles(u, req.roles());
        }
        audit.record(AuditService.Actions.UPDATE, "USER", u.getId(), null,
                AuditService.snapshot("email", u.getEmail()));
        return toDto(u);
    }

    @Transactional
    public AdminUserDto setStatus(UUID id, String frontendStatus) {
        User u = require(id);
        u.setStatus(fromFrontendStatus(frontendStatus));
        audit.record(AuditService.Actions.UPDATE, "USER", u.getId(), null,
                AuditService.snapshot("status", u.getStatus().name()));
        return toDto(u);
    }

    @Transactional
    public void delete(UUID id) {
        User u = require(id);
        users.delete(u); // soft-delete via @SQLDelete on User
        audit.record(AuditService.Actions.DELETE, "USER", id, null,
                AuditService.snapshot("email", u.getEmail()));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private User require(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));
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
