package com.eduplatform.eduplatform_backend.common.scheduled;

import com.eduplatform.eduplatform_backend.audit.service.ApiLogService;
import com.eduplatform.eduplatform_backend.identity.repo.AdminRegistrationOtpRepository;
import com.eduplatform.eduplatform_backend.identity.repo.AuthActionTokenRepository;
import com.eduplatform.eduplatform_backend.identity.repo.OAuthAuthStateRepository;
import com.eduplatform.eduplatform_backend.identity.repo.RefreshTokenRepository;
import com.eduplatform.eduplatform_backend.identity.repo.TutorRegistrationOtpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Periodic housekeeping: purges expired OTPs, OAuth states, and stale refresh tokens. */
@Component
public class CleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(CleanupScheduler.class);

    private final TutorRegistrationOtpRepository tutorOtps;
    private final AdminRegistrationOtpRepository adminOtps;
    private final OAuthAuthStateRepository oauthStates;
    private final RefreshTokenRepository refreshTokens;
    private final AuthActionTokenRepository authActionTokens;
    private final ApiLogService apiLogs;
    private final int apiLogRetentionDays;

    public CleanupScheduler(TutorRegistrationOtpRepository tutorOtps,
                            AdminRegistrationOtpRepository adminOtps,
                            OAuthAuthStateRepository oauthStates,
                            RefreshTokenRepository refreshTokens,
                            AuthActionTokenRepository authActionTokens,
                            ApiLogService apiLogs,
                            @Value("${app.cleanup.api-log-retention-days:30}") int apiLogRetentionDays) {
        this.tutorOtps = tutorOtps;
        this.adminOtps = adminOtps;
        this.oauthStates = oauthStates;
        this.refreshTokens = refreshTokens;
        this.authActionTokens = authActionTokens;
        this.apiLogs = apiLogs;
        this.apiLogRetentionDays = apiLogRetentionDays;
    }

    /** Runs at the top of every hour. */
    @Scheduled(cron = "${app.cleanup.cron:0 0 * * * *}")
    @Transactional
    public void purgeExpired() {
        Instant now = Instant.now();
        int tutor = tutorOtps.deleteExpired(now);
        int admin = adminOtps.deleteExpired(now);
        int states = oauthStates.deleteExpired(now);
        int actionTokens = authActionTokens.deleteExpired(now);
        // Keep recently-expired refresh tokens a week for family-reuse forensics, then drop them.
        int tokens = refreshTokens.deleteExpired(now.minus(7, ChronoUnit.DAYS));
        int apiLogRows = apiLogs.purgeOlderThan(now.minus(apiLogRetentionDays, ChronoUnit.DAYS));
        if (tutor + admin + states + actionTokens + tokens + apiLogRows > 0) {
            log.info("Cleanup purged tutorOtps={} adminOtps={} oauthStates={} authActionTokens={} refreshTokens={} apiLogs={}",
                    tutor, admin, states, actionTokens, tokens, apiLogRows);
        }
    }
}
