package com.eduplatform.eduplatform_backend.identity.service;

import com.eduplatform.eduplatform_backend.common.enums.AuthActionPurpose;
import com.eduplatform.eduplatform_backend.common.enums.TokenRevokeReason;
import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.mail.MailService;
import com.eduplatform.eduplatform_backend.common.security.TokenHasher;
import com.eduplatform.eduplatform_backend.identity.domain.AuthActionToken;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.AuthActionTokenRepository;
import com.eduplatform.eduplatform_backend.identity.repo.RefreshTokenRepository;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Password reset + email verification flows (token emailed; only its hash is stored). */
@Service
public class AccountRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(AccountRecoveryService.class);
    private static final Duration RESET_TTL = Duration.ofMinutes(30);
    private static final Duration VERIFY_TTL = Duration.ofHours(24);

    private final UserRepository users;
    private final AuthActionTokenRepository tokens;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final MailService mail;
    private final String frontendBaseUrl;

    public AccountRecoveryService(UserRepository users, AuthActionTokenRepository tokens,
                                  RefreshTokenRepository refreshTokens, PasswordEncoder encoder, MailService mail,
                                  @Value("${app.frontend.public-url:http://localhost:3000}") String frontendBaseUrl) {
        this.users = users;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.mail = mail;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /** Always succeeds (never reveals whether the email exists). */
    @Transactional
    public void requestPasswordReset(String email) {
        User user = users.findByEmailIgnoreCase(email).orElse(null);
        if (user == null || user.getPasswordHash() == null || user.getStatus() == UserStatus.DELETED) {
            log.info("[password-reset] no eligible account for {}", email);
            return;
        }
        String raw = TokenHasher.randomToken(32);
        persist(user.getId(), AuthActionPurpose.PASSWORD_RESET, raw, RESET_TTL);
        String link = frontendBaseUrl + "/reset-password?token=" + raw;
        mail.send(user.getEmail(), "Reset your AzTU EduPlatform password",
                "We received a request to reset your password.\n\nReset it here (valid 30 minutes):\n" + link
                        + "\n\nIf you didn't request this, ignore this email.");
    }

    @Transactional
    public void confirmPasswordReset(String rawToken, String newPassword) {
        AuthActionToken tok = requireValid(rawToken, AuthActionPurpose.PASSWORD_RESET);
        User user = users.findById(tok.getUserId())
                .orElseThrow(() -> Errors.badRequest("INVALID_TOKEN", "Reset link is invalid or expired"));

        user.setPasswordHash(encoder.encode(newPassword));
        if (user.getStatus() == UserStatus.LOCKED) {
            user.setStatus(UserStatus.ACTIVE);
        }
        user.setFailedLogins((short) 0);
        users.save(user);

        tok.setConsumedAt(Instant.now());
        tokens.save(tok);
        // Invalidate all existing sessions after a password change.
        refreshTokens.revokeAllForUser(user.getId(), TokenRevokeReason.ADMIN, Instant.now());
    }

    @Transactional
    public void requestEmailVerification(UUID userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));
        if (user.getEmailVerifiedAt() != null) {
            throw Errors.conflict("EMAIL_ALREADY_VERIFIED", "Your email is already verified");
        }
        String raw = TokenHasher.randomToken(32);
        persist(userId, AuthActionPurpose.EMAIL_VERIFY, raw, VERIFY_TTL);
        String link = frontendBaseUrl + "/verify-email?token=" + raw;
        mail.send(user.getEmail(), "Verify your AzTU EduPlatform email",
                "Confirm your email address (valid 24 hours):\n" + link);
    }

    @Transactional
    public void confirmEmailVerification(String rawToken) {
        AuthActionToken tok = requireValid(rawToken, AuthActionPurpose.EMAIL_VERIFY);
        User user = users.findById(tok.getUserId())
                .orElseThrow(() -> Errors.badRequest("INVALID_TOKEN", "Verification link is invalid or expired"));
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(Instant.now());
            users.save(user);
        }
        tok.setConsumedAt(Instant.now());
        tokens.save(tok);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void persist(UUID userId, AuthActionPurpose purpose, String rawToken, Duration ttl) {
        // Invalidate any prior outstanding tokens of this purpose for the user.
        tokens.consumeAllForUser(userId, purpose, Instant.now());
        AuthActionToken t = AuthActionToken.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .purpose(purpose)
                .tokenHash(TokenHasher.sha256Hex(rawToken))
                .expiresAt(Instant.now().plus(ttl))
                .createdAt(Instant.now())
                .build();
        tokens.save(t);
    }

    private AuthActionToken requireValid(String rawToken, AuthActionPurpose purpose) {
        AuthActionToken tok = tokens
                .findByTokenHashAndPurposeAndConsumedAtIsNull(TokenHasher.sha256Hex(rawToken), purpose)
                .orElseThrow(() -> Errors.badRequest("INVALID_TOKEN", "Link is invalid or already used"));
        if (tok.getExpiresAt().isBefore(Instant.now())) {
            throw Errors.badRequest("TOKEN_EXPIRED", "This link has expired; request a new one");
        }
        return tok;
    }
}
