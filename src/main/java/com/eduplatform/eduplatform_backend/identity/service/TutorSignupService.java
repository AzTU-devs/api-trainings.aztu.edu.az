package com.eduplatform.eduplatform_backend.identity.service;

import com.eduplatform.eduplatform_backend.catalog.repo.CategoryRepository;
import com.eduplatform.eduplatform_backend.common.enums.TutorApprovalStatus;
import com.eduplatform.eduplatform_backend.catalog.domain.Category;
import com.eduplatform.eduplatform_backend.common.error.AppException;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.TokenHasher;
import com.eduplatform.eduplatform_backend.identity.domain.TutorRegistrationOtp;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.TutorRegistrationOtpRepository;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import com.eduplatform.eduplatform_backend.identity.web.dto.OtpStartResponse;
import com.eduplatform.eduplatform_backend.identity.web.dto.TutorRegisterRequest;
import com.eduplatform.eduplatform_backend.identity.web.dto.TutorRegisterResult;
import com.eduplatform.eduplatform_backend.identity.web.dto.TutorRegisterVerifyRequest;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorProfile;
import com.eduplatform.eduplatform_backend.tutor.service.CustomExpertise;
import com.eduplatform.eduplatform_backend.tutor.service.TutorService;
import com.eduplatform.eduplatform_backend.tutor.web.dto.TutorApplyRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Two-step tutor self-registration with OTP (public, no auth).
 *
 *   1. {@link #start}  — details validated, OTP generated + emailed, nothing persisted to users yet.
 *   2. {@link #verify} — OTP checked → User (USER role) + PENDING TutorProfile + approval request.
 *      No tokens are issued: the tutor must be approved by an admin, then sign in via the portal.
 */
@Service
public class TutorSignupService {

    private static final Logger log = LoggerFactory.getLogger(TutorSignupService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final Duration OTP_TTL = Duration.ofMinutes(10);
    private static final int OTP_LENGTH = 6;
    private static final short MAX_ATTEMPTS = 5;
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_CODES_PER_HOUR = 5;

    private final TutorRegistrationOtpRepository otps;
    private final UserRepository users;
    private final CategoryRepository categories;
    private final PasswordEncoder encoder;
    private final AuthService authService;
    private final TutorService tutorService;
    private final com.eduplatform.eduplatform_backend.common.mail.MailService mail;

    public TutorSignupService(TutorRegistrationOtpRepository otps, UserRepository users,
                              CategoryRepository categories, PasswordEncoder encoder,
                              AuthService authService, TutorService tutorService,
                              com.eduplatform.eduplatform_backend.common.mail.MailService mail) {
        this.otps = otps;
        this.users = users;
        this.categories = categories;
        this.encoder = encoder;
        this.authService = authService;
        this.tutorService = tutorService;
        this.mail = mail;
    }

    /**
     * Stores the application and mails a code. Also the "resend code" path, so each address is
     * held to one code a minute and {@link #MAX_CODES_PER_HOUR} an hour: the per-IP rate limit
     * alone would let one visitor mail any address repeatedly, and it is loose on purpose, since
     * a whole classroom signs up from the campus's one NAT address.
     */
    @Transactional
    public OtpStartResponse start(TutorRegisterRequest req) {
        if (users.existsByEmailIgnoreCase(req.email())) {
            throw Errors.conflict("EMAIL_ALREADY_REGISTERED", "An account with this email already exists");
        }
        Set<UUID> categoryIds = req.categoryIds() == null ? Set.of() : req.categoryIds();
        List<String> categoryNames = new ArrayList<>();
        for (UUID categoryId : categoryIds) {
            // TutorRegisterRequest refuses a null id already; this keeps findById from turning one
            // that got past it into a server error.
            if (categoryId == null) {
                throw Errors.badRequest("INVALID_CATEGORY", "Unknown category: null");
            }
            Category category = categories.findById(categoryId)
                    .orElseThrow(() -> Errors.badRequest("INVALID_CATEGORY", "Unknown category: " + categoryId));
            if (!category.isActive()) {
                throw Errors.badRequest("CATEGORY_INACTIVE", "Category " + category.getName() + " is not offered");
            }
            categoryNames.add(category.getName());
        }
        // Held to the rules here, before a code is mailed, so the verify step never has to refuse
        // an application the applicant can no longer correct (see TutorService.applyFromSignup).
        List<String> customExpertise = CustomExpertise.normalize(req.customExpertise(), categoryNames);
        CustomExpertise.requireAny(categoryIds, customExpertise);
        Instant issuedBefore = Instant.now();
        Instant last = otps.lastIssuedAt(req.email()).orElse(null);
        if (last != null && last.isAfter(issuedBefore.minus(RESEND_COOLDOWN))) {
            throw tooSoon(Duration.between(issuedBefore, last.plus(RESEND_COOLDOWN)));
        }
        if (otps.countIssuedSince(req.email(), issuedBefore.minus(Duration.ofHours(1))) >= MAX_CODES_PER_HOUR) {
            throw tooSoon(Duration.ofHours(1));
        }
        // Revoke any prior pending OTP so only the freshest one is valid.
        otps.findActiveByEmail(req.email()).ifPresent(o -> {
            o.setConsumedAt(Instant.now());
            otps.save(o);
        });

        String otp = generateNumericOtp();
        Instant now = Instant.now();

        TutorRegistrationOtp row = TutorRegistrationOtp.builder()
                .id(UUID.randomUUID())
                .email(req.email())
                .firstName(req.firstName())
                .lastName(req.lastName())
                .phone(req.phone())
                .passwordHash(encoder.encode(req.password()))
                .headline(trimToNull(req.headline()))
                .bio(trimToNull(req.bio()))
                .yearsExperience(req.yearsExperience())
                // Stored trimmed, blank as nothing: the values are validated as web addresses,
                // and a blank field means the applicant left it empty.
                .websiteUrl(trimToNull(req.websiteUrl()))
                .linkedinUrl(trimToNull(req.linkedinUrl()))
                .locale(normalisedLocale(req.locale()))
                .categoryIds(categoryIds.stream().map(UUID::toString).toList())
                .customExpertise(customExpertise)
                .otpHash(TokenHasher.sha256Hex(otp))
                .attempts((short) 0)
                .createdAt(now)
                .expiresAt(now.plus(OTP_TTL))
                .build();
        otps.save(row);

        sendOtp(req.email(), otp);
        return new OtpStartResponse(
                "An OTP has been sent. Submit it to /api/auth/register/tutor/verify within "
                        + OTP_TTL.toMinutes() + " minutes.",
                row.getExpiresAt(),
                OTP_LENGTH);
    }

    @Transactional
    public TutorRegisterResult verify(TutorRegisterVerifyRequest req) {
        TutorRegistrationOtp row = otps.findActiveByEmail(req.email())
                .orElseThrow(() -> Errors.notFound("OTP_NOT_FOUND",
                        "No pending tutor registration for this email; start signup first"));

        if (Instant.now().isAfter(row.getExpiresAt())) {
            otps.delete(row);
            throw Errors.unprocessable("OTP_EXPIRED", "OTP has expired; please start signup again");
        }
        if (row.getAttempts() >= MAX_ATTEMPTS) {
            row.setConsumedAt(Instant.now());
            otps.save(row);
            throw Errors.unprocessable("OTP_TOO_MANY_ATTEMPTS", "Too many failed attempts; please start signup again");
        }
        if (!row.getOtpHash().equals(TokenHasher.sha256Hex(req.otp()))) {
            // Committed separately: the 401 below rolls this transaction back.
            otps.recordFailedAttemptAndCommit(row.getId());
            throw Errors.unauthorized("OTP_INVALID",
                    "OTP is incorrect (" + (MAX_ATTEMPTS - row.getAttempts() - 1) + " attempts remaining)");
        }
        if (users.existsByEmailIgnoreCase(row.getEmail())) {
            otps.delete(row);
            throw Errors.conflict("EMAIL_ALREADY_REGISTERED", "An account with this email was created concurrently");
        }

        User user = authService.createUserWithUserRolePreHashed(
                row.getEmail(), row.getPasswordHash(), row.getFirstName(),
                row.getLastName(), row.getPhone(), normalisedLocale(row.getLocale()));
        // The code just proved the applicant reads this inbox, which is what verifying an email
        // address means; the dashboard used to show every expert who signed up as "Unverified".
        user.setEmailVerifiedAt(Instant.now());

        Set<UUID> categoryIds = new LinkedHashSet<>();
        for (String id : row.getCategoryIds()) categoryIds.add(UUID.fromString(id));
        // The start step checked these; one an admin has hidden or deleted in the minutes since is
        // dropped rather than failing the sign-up after the code was already proven, the same way
        // apply() drops stored links that no longer pass.
        categoryIds.removeIf(id -> categories.findById(id).map(c -> !c.isActive()).orElse(true));

        TutorProfile profile = tutorService.applyFromSignup(user.getId(), new TutorApplyRequest(
                row.getHeadline(), row.getBio(), row.getYearsExperience(),
                row.getWebsiteUrl(), row.getLinkedinUrl(), categoryIds, row.getCustomExpertise()));

        row.setConsumedAt(Instant.now());
        otps.save(row);

        return new TutorRegisterResult(
                "Your tutor application was submitted and is pending admin approval. "
                        + "Once approved, sign in from the portal.",
                profile.getId(),
                TutorApprovalStatus.PENDING);
    }

    private void sendOtp(String email, String otp) {
        mail.sendOtp(email, otp, "tutor registration", OTP_TTL.toMinutes());
    }

    private static AppException tooSoon(Duration wait) {
        long seconds = Math.max(1, wait.toSeconds());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        return new AppException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RESEND_TOO_SOON",
                "A code was sent to this address moments ago; wait before asking for another", headers);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** az or en, the languages the site is in; anything else, or nothing, is English. */
    private static String normalisedLocale(String locale) {
        return locale != null && locale.trim().equalsIgnoreCase("az") ? "az" : "en";
    }

    private static String generateNumericOtp() {
        StringBuilder sb = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) sb.append(RNG.nextInt(10));
        return sb.toString();
    }
}
