package com.eduplatform.eduplatform_backend.identity.service;

import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Records failed-login bookkeeping in its own transaction so the increment + auto-lock
 * survive the rollback of the (intentionally failing) login attempt.
 */
@Service
public class LoginSecurityService {

    private final UserRepository users;
    private final int maxFailedLogins;

    public LoginSecurityService(UserRepository users,
                                @Value("${app.security.max-failed-logins:5}") int maxFailedLogins) {
        this.users = users;
        this.maxFailedLogins = maxFailedLogins;
    }

    /** Increment failed-login count; lock the account once the threshold is reached. Returns true if now locked. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean registerFailedAttempt(UUID userId) {
        User u = users.findById(userId).orElse(null);
        if (u == null) return false;
        short attempts = (short) (u.getFailedLogins() + 1);
        u.setFailedLogins(attempts);
        boolean locked = attempts >= maxFailedLogins;
        if (locked && u.getStatus() == UserStatus.ACTIVE) {
            u.setStatus(UserStatus.LOCKED);
        }
        users.save(u);
        return locked;
    }
}
