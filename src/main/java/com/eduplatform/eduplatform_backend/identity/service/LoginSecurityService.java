package com.eduplatform.eduplatform_backend.identity.service;

import com.eduplatform.eduplatform_backend.common.security.TokenHasher;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The failed-password throttle. After {@code app.security.max-failed-logins} wrong passwords in a
 * row for one address typed, from one client address, sign-in to that address is refused from
 * that client address for {@code app.security.lockout-minutes}, and then simply works again.
 *
 * <p>Why per client address. The lock used to be kept on the account alone and answered before
 * the password was looked at, so anybody who knew an address — a SUPER_ADMIN's included — could
 * keep its owner out indefinitely with five wrong passwords every fifteen minutes; an admin unlock
 * or a password reset lasted until the next five. Now the guesser locks out only the address the
 * guesses come from, and the owner signs in from anywhere else as usual. What the throttle still
 * does is what it is for: one source gets five guesses per account per quarter hour, on top of the
 * per-address request budget of the auth rate limiter.
 *
 * <p>Why per address typed rather than per account. The same rule then holds whether or not an
 * account exists: an unknown address locks after five wrong passwords exactly as a real one does,
 * so a 429 tells the caller nothing — an account-only lock (unknown addresses never locked) told
 * anyone who kept guessing which addresses are registered. The key is a hash of the lower-cased
 * address, so the table holds no list of the addresses people have tried.
 *
 * <p>The account still records what happens to it, for the dashboard: {@code failed_logins}
 * counts wrong passwords since the last sign-in, and {@code locked_until} is the end of the
 * latest lockout any client address got, which is what the Users page shows as LOCKED and what
 * Unlock clears — together with every client address's lock ({@link #releaseAll}).
 *
 * <p>The failure is written in its own transaction, so it survives the rollback of the
 * (intentionally failing) sign-in.
 */
@Service
public class LoginSecurityService {

    /** Stands in for a client address that could not be determined, e.g. outside a request. */
    static final String UNKNOWN_CLIENT = "unknown";

    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final int maxFailedLogins;
    private final int lockoutMinutes;

    public LoginSecurityService(UserRepository users, JdbcTemplate jdbc,
                                @Value("${app.security.max-failed-logins:5}") int maxFailedLogins,
                                @Value("${app.security.lockout-minutes:15}") long lockoutMinutes) {
        this.users = users;
        this.jdbc = jdbc;
        this.maxFailedLogins = Math.max(1, maxFailedLogins);
        this.lockoutMinutes = (int) Math.max(1, Math.min(Integer.MAX_VALUE, lockoutMinutes));
    }

    /**
     * How long the lock on sign-in to {@code email} from {@code clientIp} still holds, or null when
     * the password may be checked. Asked before the password is, so a guesser learns nothing from
     * what it tries while locked out. Measured by the database, which set the lock with its own
     * clock: subtracting the application's clock from it could read past the lockout's length.
     */
    public Duration lockRemaining(String email, String clientIp) {
        List<Long> seconds = jdbc.queryForList("""
                select cast(ceil(extract(epoch from locked_until - now())) as bigint) from login_throttles
                where email_hash = ? and client_ip = ? and locked_until > now()
                """, Long.class, emailKey(email), clientKey(clientIp));
        return seconds.isEmpty() ? null : Duration.ofSeconds(Math.max(1, seconds.get(0)));
    }

    /**
     * Counts a wrong password for {@code email} from {@code clientIp}; the one that reaches the
     * threshold starts a lockout for that pair and resets its count, so the next lockout needs a
     * full run of failures again. Returns true if this attempt started a lockout.
     *
     * @param userId the account behind {@code email}, or null when there is none
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean registerFailedAttempt(String email, UUID userId, String clientIp) {
        // One statement, so two wrong passwords arriving together are both counted: the conflict
        // path takes the row lock and sees the other's increment. The times are the database's,
        // so "did this attempt start the lock" is an exact comparison of two values it computed.
        List<Timestamp> started = jdbc.queryForList("""
                insert into login_throttles (email_hash, client_ip, failures, locked_until, last_failed_at)
                values (?, ?, case when 1 >= ? then 0 else 1 end,
                        case when 1 >= ? then now() + make_interval(mins => ?) end, now())
                on conflict (email_hash, client_ip) do update set
                    failures = case when login_throttles.failures + 1 >= ? then 0
                                    else login_throttles.failures + 1 end,
                    locked_until = case when login_throttles.failures + 1 >= ?
                                        then now() + make_interval(mins => ?)
                                        else login_throttles.locked_until end,
                    last_failed_at = now()
                returning case when locked_until = last_failed_at + make_interval(mins => ?)
                               then locked_until end
                """, Timestamp.class,
                emailKey(email), clientKey(clientIp), maxFailedLogins, maxFailedLogins, lockoutMinutes,
                maxFailedLogins, maxFailedLogins, lockoutMinutes, lockoutMinutes);
        Instant lockEnd = started.isEmpty() || started.get(0) == null ? null : started.get(0).toInstant();
        if (userId != null) {
            users.findById(userId).ifPresent(u -> {
                u.setFailedLogins((short) Math.min(Short.MAX_VALUE, u.getFailedLogins() + 1));
                if (lockEnd != null && (u.getLockedUntil() == null || u.getLockedUntil().isBefore(lockEnd))) {
                    u.setLockedUntil(lockEnd);
                }
                users.save(u);
            });
        }
        return lockEnd != null;
    }

    /**
     * A correct password from this client address ends its throttle for this address typed. Other
     * client addresses keep theirs: the owner signing in must not reset a guesser's count.
     */
    public void clearAddress(String email, String clientIp) {
        jdbc.update("delete from login_throttles where email_hash = ? and client_ip = ?",
                emailKey(email), clientKey(clientIp));
    }

    /**
     * Ends every lockout of {@code user}'s address, from every client address, and the account's
     * own record of it: for an administrator's Unlock or re-enable, and for a password reset,
     * which proves the owner holds the mailbox.
     */
    public void releaseAll(User user) {
        jdbc.update("delete from login_throttles where email_hash = ?", emailKey(user.getEmail()));
        user.setFailedLogins((short) 0);
        user.setLockedUntil(null);
    }

    /** Deletes throttle rows nobody has failed on for a day and whose lock, if any, is over. */
    @Transactional
    public int purgeStale() {
        return jdbc.update("""
                delete from login_throttles
                where last_failed_at < now() - interval '1 day'
                  and (locked_until is null or locked_until < now())
                """);
    }

    /**
     * The end of the latest lockout recorded on the account, or null when none is in force: what
     * the dashboard shows as LOCKED. Informational — sign-in is refused per client address, by
     * {@link #lockRemaining(String, String)}.
     */
    public static Instant activeLock(User user, Instant now) {
        Instant until = user.getLockedUntil();
        return until != null && until.isAfter(now) ? until : null;
    }

    private static String emailKey(String email) {
        String normalised = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return TokenHasher.sha256Hex(normalised);
    }

    private static String clientKey(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return UNKNOWN_CLIENT;
        return clientIp.length() > 64 ? clientIp.substring(0, 64) : clientIp;
    }
}
