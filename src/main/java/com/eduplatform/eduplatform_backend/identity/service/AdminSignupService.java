package com.eduplatform.eduplatform_backend.identity.service;

import com.eduplatform.eduplatform_backend.common.enums.NotificationChannel;
import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.TokenHasher;
import com.eduplatform.eduplatform_backend.identity.domain.AdminRegistrationOtp;
import com.eduplatform.eduplatform_backend.identity.domain.Role;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.domain.UserRole;
import com.eduplatform.eduplatform_backend.identity.domain.UserRoleId;
import com.eduplatform.eduplatform_backend.identity.repo.AdminRegistrationOtpRepository;
import com.eduplatform.eduplatform_backend.identity.repo.RoleRepository;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import com.eduplatform.eduplatform_backend.identity.web.dto.AdminRegisterStartRequest;
import com.eduplatform.eduplatform_backend.identity.web.dto.AdminRegisterStartResponse;
import com.eduplatform.eduplatform_backend.identity.web.dto.AdminRegisterVerifyRequest;
import com.eduplatform.eduplatform_backend.identity.web.dto.AuthTokens;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Two-step admin self-registration with OTP. v1 keeps the endpoint open so the
 * first admin can bootstrap; harden later by requiring an invite token signed by
 * a SUPER_ADMIN before allowing {@link #start(AdminRegisterStartRequest, HttpServletRequest)}.
 *
 * Flow:
 *   1. POST /api/auth/admin/register/start  — details validated, OTP generated, emailed.
 *   2. POST /api/auth/admin/register/verify — OTP checked, user created with ADMIN role,
 *      JWT + refresh token returned.
 */
@Service
public class AdminSignupService {

    private static final Logger log = LoggerFactory.getLogger(AdminSignupService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final Duration OTP_TTL = Duration.ofMinutes(10);
    private static final int OTP_LENGTH = 6;
    private static final short MAX_ATTEMPTS = 5;

    private final AdminRegistrationOtpRepository otps;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final AuthService authService;
    private final com.eduplatform.eduplatform_backend.common.mail.MailService mail;
    private final boolean selfRegisterEnabled;

    public AdminSignupService(AdminRegistrationOtpRepository otps, UserRepository users, RoleRepository roles,
                              PasswordEncoder encoder, AuthService authService,
                              com.eduplatform.eduplatform_backend.common.mail.MailService mail,
                              @org.springframework.beans.factory.annotation.Value("${app.security.admin-self-register-enabled:false}")
                              boolean selfRegisterEnabled) {
        this.otps = otps;
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.authService = authService;
        this.mail = mail;
        this.selfRegisterEnabled = selfRegisterEnabled;
    }

    @Transactional
    public AdminRegisterStartResponse start(AdminRegisterStartRequest req, HttpServletRequest http) {
        // Bootstrap-only: once any admin exists, self-registration is closed unless explicitly enabled.
        if (!selfRegisterEnabled && users.countByRoleCodes(java.util.List.of(
                com.eduplatform.eduplatform_backend.common.enums.RoleCode.ADMIN,
                com.eduplatform.eduplatform_backend.common.enums.RoleCode.SUPER_ADMIN)) > 0) {
            throw Errors.forbidden("ADMIN_REGISTER_CLOSED",
                    "Admin self-registration is disabled; ask an existing administrator to create your account");
        }
        if (users.existsByEmailIgnoreCase(req.email())) {
            throw Errors.conflict("EMAIL_ALREADY_REGISTERED", "An account with this email already exists");
        }
        // If an active OTP exists, revoke it so a fresh one is issued.
        otps.findActiveByEmail(req.email()).ifPresent(o -> {
            o.setConsumedAt(Instant.now());
            otps.save(o);
        });

        String otp = generateNumericOtp();
        Instant now = Instant.now();

        AdminRegistrationOtp row = AdminRegistrationOtp.builder()
                .id(UUID.randomUUID())
                .email(req.email())
                .firstName(req.firstName())
                .lastName(req.lastName())
                .phone(req.phoneNumber())
                .finKod(req.finKod())
                .passwordHash(encoder.encode(req.password()))
                .otpHash(TokenHasher.sha256Hex(otp))
                .attempts((short) 0)
                .createdAt(now)
                .expiresAt(now.plus(OTP_TTL))
                .build();
        otps.save(row);

        sendOtp(req, otp);
        return new AdminRegisterStartResponse(
                "An OTP has been sent. Submit it to /api/auth/admin/register/verify within "
                        + OTP_TTL.toMinutes() + " minutes.",
                row.getExpiresAt(),
                OTP_LENGTH);
    }

    @Transactional
    public AuthTokens verify(AdminRegisterVerifyRequest req, HttpServletRequest http) {
        AdminRegistrationOtp row = otps.findActiveByEmail(req.email())
                .orElseThrow(() -> Errors.notFound("OTP_NOT_FOUND",
                        "No pending admin registration for this email; start signup first"));

        if (Instant.now().isAfter(row.getExpiresAt())) {
            otps.delete(row);
            throw Errors.unprocessable("OTP_EXPIRED", "OTP has expired; please start signup again");
        }
        if (row.getAttempts() >= MAX_ATTEMPTS) {
            row.setConsumedAt(Instant.now());
            otps.save(row);
            throw Errors.unprocessable("OTP_TOO_MANY_ATTEMPTS",
                    "Too many failed attempts; please start signup again");
        }
        if (!row.getOtpHash().equals(TokenHasher.sha256Hex(req.otp()))) {
            row.setAttempts((short) (row.getAttempts() + 1));
            otps.save(row);
            throw Errors.unauthorized("OTP_INVALID",
                    "OTP is incorrect (" + (MAX_ATTEMPTS - row.getAttempts()) + " attempts remaining)");
        }

        // Race-guard: another request might have created the same email in the meantime.
        if (users.existsByEmailIgnoreCase(row.getEmail())) {
            otps.delete(row);
            throw Errors.conflict("EMAIL_ALREADY_REGISTERED", "An account with this email was created concurrently");
        }

        User user = createAdminUser(row);
        row.setConsumedAt(Instant.now());
        otps.save(row);

        return authService.issueTokens(user, http);
    }

    private User createAdminUser(AdminRegistrationOtp row) {
        Role adminRole = roles.findByCode(RoleCode.ADMIN)
                .orElseThrow(() -> new IllegalStateException("Role ADMIN missing — V2 seed migration did not run"));

        User user = User.builder()
                .email(row.getEmail())
                .phone(row.getPhone())
                .passwordHash(row.getPasswordHash())
                .firstName(row.getFirstName())
                .lastName(row.getLastName())
                .status(UserStatus.ACTIVE)
                .locale("en")
                .finKod(row.getFinKod())
                .emailVerifiedAt(Instant.now())     // OTP confirms ownership of the inbox
                .build();
        user.setId(UUID.randomUUID());

        // Attach the ADMIN role link to the collection BEFORE persisting. Because
        // the id is assigned manually, save() runs as a merge — saving first and
        // mutating the (now detached) entity afterwards would silently drop the
        // role link, leaving the admin with no roles. Adding it up-front lets the
        // cascade=ALL on User.userRoles persist the link, and we return the managed
        // instance so token issuance reflects the persisted role.
        UserRole link = UserRole.builder()
                .id(new UserRoleId(user.getId(), adminRole.getId()))
                .user(user)
                .role(adminRole)
                .grantedAt(Instant.now())
                .build();
        user.getUserRoles().add(link);

        return users.save(user);
    }

    private void sendOtp(AdminRegisterStartRequest req, String otp) {
        mail.sendOtp(req.email(), otp, "admin registration", OTP_TTL.toMinutes());
    }

    private static String generateNumericOtp() {
        StringBuilder sb = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) sb.append(RNG.nextInt(10));
        return sb.toString();
    }
}
