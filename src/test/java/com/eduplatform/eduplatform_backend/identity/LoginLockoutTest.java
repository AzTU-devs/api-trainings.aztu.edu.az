package com.eduplatform.eduplatform_backend.identity;

import com.eduplatform.eduplatform_backend.common.security.TokenHasher;
import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failed-login lockout is temporary, shuts out only the client address the wrong passwords came
 * from, and says nothing about accounts to a guesser.
 *
 * <p>It used to set status LOCKED with no expiry, which only a SUPER_ADMIN could lift — so anyone
 * who knew an address could lock its owner out for good, every SUPER_ADMIN included — and the
 * status was checked before the password, so any wrong password told the caller whether the
 * address had an account and whether it was locked or disabled. The temporary lock that replaced
 * it was still kept on the account alone: five wrong passwords every fifteen minutes kept the
 * owner out indefinitely, from anywhere.
 *
 * <p>The test client connects over loopback, which Tomcat trusts as a proxy, so X-Forwarded-For
 * stands for the client address a real proxy reports; addresses are from 10.230.0.0/16.
 */
class LoginLockoutTest extends AbstractIntegrationTest {

    @Test
    void fiveWrongPasswordsLockPasswordSignInForAWhileOnly() {
        TestUser user = newUser("lockout", "USER");
        String client = "10.230.1.1";
        for (int i = 0; i < 5; i++) {
            login(user.email(), "wrong-password", client).expectError(401, "INVALID_CREDENTIALS");
        }

        ApiClient.Response locked = login(user.email(), PASSWORD, client)
                .expectError(429, "LOGIN_TEMPORARILY_LOCKED");
        assertThat(locked.header("Retry-After")).isPresent();
        assertThat(Long.parseLong(locked.header("Retry-After").orElseThrow())).isBetween(1L, 15L * 60);
        assertThat(statusOf(user.id())).as("a lockout is not a status").isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select locked_until is not null from users where id = ?",
                Boolean.class, user.id())).as("the account records it for the Users page").isTrue();

        // Fifteen minutes later, the correct password works again and clears the lock.
        jdbc.update("update login_throttles set locked_until = now() - interval '1 second' where email_hash = ?",
                emailKey(user.email()));
        login(user.email(), PASSWORD, client).expectStatus(200);
        assertThat(jdbc.queryForObject("select locked_until is null and failed_logins = 0 from users where id = ?",
                Boolean.class, user.id())).isTrue();
        assertThat(throttleRows(user.email())).isZero();
    }

    /**
     * The owner is not shut out by someone else's guesses: the lock holds the address the guesses
     * came from, however long the guesser keeps at it, and nothing else.
     */
    @Test
    void aLockShutsOutOnlyTheClientAddressTheGuessesCameFrom() {
        TestUser admin = newUser("guessed-at", "SUPER_ADMIN");
        String guesser = "10.230.2.1";
        for (int i = 0; i < 5; i++) {
            login(admin.email(), "guess-" + i, guesser).expectError(401, "INVALID_CREDENTIALS");
        }
        login(admin.email(), PASSWORD, guesser).expectError(429, "LOGIN_TEMPORARILY_LOCKED");

        login(admin.email(), PASSWORD, "10.230.2.2").expectStatus(200);

        // Signing in from elsewhere does not reset the guesser's lock or its count.
        login(admin.email(), PASSWORD, guesser).expectError(429, "LOGIN_TEMPORARILY_LOCKED");
    }

    /** The lockout is answered before the password is checked, so it cannot be used to keep guessing. */
    @Test
    void whileLockedEvenTheCorrectPasswordIsNotChecked() {
        TestUser user = newUser("locked-guess", "USER");
        String client = "10.230.3.1";
        jdbc.update("""
                insert into login_throttles (email_hash, client_ip, failures, locked_until, last_failed_at)
                values (?, ?, 0, now() + interval '10 minutes', now())
                """, emailKey(user.email()), client);

        login(user.email(), "wrong-password", client).expectError(429, "LOGIN_TEMPORARILY_LOCKED");
        login(user.email(), PASSWORD, client).expectError(429, "LOGIN_TEMPORARILY_LOCKED");
        assertThat(jdbc.queryForObject("select failed_logins from users where id = ?", Integer.class, user.id()))
                .as("attempts during a lockout are not counted").isZero();
    }

    /**
     * An unknown address locks exactly as a registered one does. It never locked before, so the
     * sixth attempt's answer (401 or 429) told a guesser whether the address had an account.
     */
    @Test
    void anUnknownAddressLocksLikeAKnownOne() {
        String nobody = "nobody-" + word() + "@it.eduplatform.test";
        TestUser somebody = newUser("somebody", "USER");
        String client = "10.230.4.1";
        for (int i = 0; i < 5; i++) {
            login(nobody, "wrong-password", client).expectError(401, "INVALID_CREDENTIALS");
            login(somebody.email(), "wrong-password", client).expectError(401, "INVALID_CREDENTIALS");
        }

        login(nobody, "wrong-password", client).expectError(429, "LOGIN_TEMPORARILY_LOCKED");
        login(somebody.email(), "wrong-password", client).expectError(429, "LOGIN_TEMPORARILY_LOCKED");
        assertThat(jdbc.queryForObject("select count(*) from login_throttles where client_ip = ?", Integer.class,
                client)).as("keyed on hashes, with no address in the table").isEqualTo(2);
    }

    @Test
    void aWrongPasswordNeverRevealsTheAccountsStatus() {
        TestUser suspended = newUser("suspended", "USER");
        jdbc.update("update users set status = 'SUSPENDED' where id = ?", suspended.id());

        loginResponse(suspended.email(), "wrong-password").expectError(401, "INVALID_CREDENTIALS");
        loginResponse("nobody-" + word() + "@it.eduplatform.test", "wrong-password")
                .expectError(401, "INVALID_CREDENTIALS");
        // Only the right password learns that the account is disabled.
        loginResponse(suspended.email(), PASSWORD).expectError(403, "ACCOUNT_NOT_ACTIVE");
    }

    /** A social-only account answers like a wrong password, not "use Google", which would confirm it. */
    @Test
    void anAccountWithoutAPasswordAnswersLikeAWrongPassword() {
        TestUser social = newUser("social-only", "USER");
        jdbc.update("update users set password_hash = null where id = ?", social.id());

        loginResponse(social.email(), PASSWORD).expectError(401, "INVALID_CREDENTIALS");
    }

    /** A row still LOCKED from before V14 is a lock like any other: the right password ends it. */
    @Test
    void aLegacyLockedStatusIsLiftedByTheCorrectPassword() {
        TestUser user = newUser("legacy-locked", "USER");
        jdbc.update("update users set status = 'LOCKED' where id = ?", user.id());

        loginResponse(user.email(), PASSWORD).expectStatus(200);
        assertThat(statusOf(user.id())).isEqualTo("ACTIVE");
    }

    /** Unlocking ends a lockout — from every client address — but never re-enables a disabled account. */
    @Test
    void unlockClearsTheLockButKeepsASuspension() {
        TestUser locked = newUser("unlock-me", "USER");
        TestUser suspended = newUser("stay-suspended", "USER");
        String client = "10.230.5.1";
        for (int i = 0; i < 5; i++) {
            login(locked.email(), "wrong-password", client).expectError(401, "INVALID_CREDENTIALS");
        }
        jdbc.update("update users set status = 'SUSPENDED', locked_until = now() + interval '10 minutes' where id = ?",
                suspended.id());
        String superToken = login(newUser("super", "SUPER_ADMIN").email());

        api.post("/api/super/security/unlock/" + locked.id()).bearer(superToken).send().expectStatus(204);
        api.post("/api/super/security/unlock/" + suspended.id()).bearer(superToken).send().expectStatus(204);

        login(locked.email(), PASSWORD, client).expectStatus(200);
        assertThat(statusOf(suspended.id())).isEqualTo("SUSPENDED");
        loginResponse(suspended.email(), PASSWORD).expectError(403, "ACCOUNT_NOT_ACTIVE");
    }

    /**
     * Changing the password from Settings asks for the current one; wrong ones count towards the
     * same lock as wrong sign-ins, so it is no side door for guessing.
     */
    @Test
    void aPasswordChangeFromSettingsCountsWrongCurrentPasswordsTowardsTheSameLock() {
        TestUser user = newUser("change-guess", "USER");
        String token = login(user.email());
        String client = "10.230.6.1";
        for (int i = 0; i < 5; i++) {
            api.post("/api/auth/password/change").bearer(token).header("X-Forwarded-For", client)
                    .json(Json.object("currentPassword", "guess-" + i, "newPassword", "NewPassword123"))
                    .send().expectError(400, "INVALID_CURRENT_PASSWORD");
        }
        api.post("/api/auth/password/change").bearer(token).header("X-Forwarded-For", client)
                .json(Json.object("currentPassword", PASSWORD, "newPassword", "NewPassword123"))
                .send().expectError(429, "LOGIN_TEMPORARILY_LOCKED");
    }

    private ApiClient.Response login(String email, String password, String clientAddress) {
        return api.post("/api/auth/login").header("X-Forwarded-For", clientAddress)
                .json(Json.object("email", email, "password", password)).send();
    }

    private int throttleRows(String email) {
        return jdbc.queryForObject("select count(*) from login_throttles where email_hash = ?", Integer.class,
                emailKey(email));
    }

    private static String emailKey(String email) {
        return TokenHasher.sha256Hex(email.trim().toLowerCase(Locale.ROOT));
    }

    private String statusOf(UUID userId) {
        return jdbc.queryForObject("select status from users where id = ?", String.class, userId);
    }
}
