package com.eduplatform.eduplatform_backend.identity;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.eduplatform.eduplatform_backend.support.Json.texts;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an administrator takes away from an account has to end what the account is signed in
 * with. Disabling, deleting or re-passwording a user used to end nothing: the refresh endpoint
 * never looked at the account, so every rotation handed out another 30-day token (and for a
 * deleted user answered 500), and an access token kept its roles for its whole 15 minutes.
 */
class SessionRevocationTest extends AbstractIntegrationTest {

    @Test
    void aDisabledAccountCanNeitherRefreshNorUseItsAccessToken() {
        TestUser user = newUser("disabled", "USER");
        JsonNode session = loginResponse(user.email(), PASSWORD).expectStatus(200).data();

        api.patch("/api/admin/users/" + user.id() + "/status").bearer(newAdminToken())
                .json(Json.object("status", "DISABLED")).send().expectStatus(200);

        refresh(session.path("refreshToken").asText()).expectStatus(401);
        api.get("/api/auth/me").bearer(session.path("accessToken").asText()).send()
                .expectError(401, "TOKEN_STALE");
        assertThat(liveRefreshTokens(user.id())).isZero();
    }

    /** A soft-deleted account's refresh used to fail on a proxy its restriction hid: a 500. */
    @Test
    void aDeletedAccountsRefreshIsA401NotA500() {
        TestUser user = newUser("deleted", "USER");
        JsonNode session = loginResponse(user.email(), PASSWORD).expectStatus(200).data();

        api.delete("/api/admin/users/" + user.id()).bearer(newAdminToken()).send().expectStatus(204);

        refresh(session.path("refreshToken").asText()).expectStatus(401);
        api.get("/api/auth/me").bearer(session.path("accessToken").asText()).send()
                .expectError(401, "TOKEN_STALE");
    }

    /** A refresh token kept from before the fix meets the account check even when it is still live. */
    @Test
    void aStillLiveRefreshTokenOfADisabledAccountIsRefused() {
        TestUser user = newUser("live-token", "USER");
        String refreshToken = loginResponse(user.email(), PASSWORD).expectStatus(200).data()
                .path("refreshToken").asText();
        jdbc.update("update users set status = 'SUSPENDED' where id = ?", user.id());

        refresh(refreshToken).expectError(401, "ACCOUNT_NOT_ACTIVE");
    }

    @Test
    void anAdminSettingANewPasswordEndsTheOldSessions() {
        TestUser user = newUser("repassworded", "USER");
        JsonNode session = loginResponse(user.email(), PASSWORD).expectStatus(200).data();

        api.put("/api/admin/users/" + user.id()).bearer(login(newUser("super", "SUPER_ADMIN").email()))
                .json(Json.object("password", "BrandNew123456")).send().expectStatus(200);

        refresh(session.path("refreshToken").asText()).expectStatus(401);
        api.get("/api/auth/me").bearer(session.path("accessToken").asText()).send()
                .expectError(401, "TOKEN_STALE");
        loginResponse(user.email(), "BrandNew123456").expectStatus(200);
    }

    /**
     * A lockout is about guessing passwords. Ending the real owner's session because somebody else
     * typed their address wrong five times would hand that somebody a way to sign anyone out.
     */
    @Test
    void aLockedOutAccountKeepsItsSession() {
        TestUser user = newUser("locked-out", "USER");
        JsonNode session = loginResponse(user.email(), PASSWORD).expectStatus(200).data();
        for (int i = 0; i < 5; i++) {
            loginResponse(user.email(), "wrong-password").expectStatus(401);
        }

        refresh(session.path("refreshToken").asText()).expectStatus(200);
        api.get("/api/auth/me").bearer(session.path("accessToken").asText()).send().expectStatus(200);
    }

    /**
     * A demoted admin kept every admin permission for the rest of their access token. The token is
     * now stale at once, and the refresh the dashboard answers that with brings the new roles.
     */
    @Test
    void aDemotedAdminLosesTheAdminPermissionsAtOnce() {
        TestUser admin = newUser("demoted", "ADMIN");
        JsonNode session = loginResponse(admin.email(), PASSWORD).expectStatus(200).data();
        String oldAccess = session.path("accessToken").asText();
        api.get("/api/admin/users").bearer(oldAccess).send().expectStatus(200);

        api.put("/api/admin/users/" + admin.id()).bearer(login(newUser("super", "SUPER_ADMIN").email()))
                .json(Json.object("roles", List.of("USER"))).send().expectStatus(200);

        api.get("/api/admin/users").bearer(oldAccess).send().expectError(401, "TOKEN_STALE");
        JsonNode refreshed = refresh(session.path("refreshToken").asText()).expectStatus(200).data();
        assertThat(texts(refreshed.path("user").path("roles"))).containsExactly("USER");
        api.get("/api/admin/users").bearer(refreshed.path("accessToken").asText()).send().expectStatus(403);
    }

    @Test
    void administratorsCannotDisableOrDeleteThemselves() {
        TestUser superAdmin = newUser("self", "SUPER_ADMIN");
        String token = login(superAdmin.email());

        api.patch("/api/admin/users/" + superAdmin.id() + "/status").bearer(token)
                .json(Json.object("status", "DISABLED")).send().expectError(403, "SELF_STATUS_CHANGE_FORBIDDEN");
        api.put("/api/admin/users/" + superAdmin.id()).bearer(token)
                .json(Json.object("status", "DISABLED")).send().expectError(403, "SELF_STATUS_CHANGE_FORBIDDEN");
        api.delete("/api/admin/users/" + superAdmin.id()).bearer(token).send()
                .expectError(403, "SELF_DELETE_FORBIDDEN");

        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, superAdmin.id()))
                .isEqualTo("ACTIVE");
    }

    /** An ADMIN resetting a fellow ADMIN's password could sign in as them. */
    @Test
    void anAdminCannotChangeAnotherAdminsAccount() {
        TestUser other = newUser("other-admin", "ADMIN");
        String adminToken = newAdminToken();

        api.put("/api/admin/users/" + other.id()).bearer(adminToken)
                .json(Json.object("password", "TakenOver123456")).send().expectError(403, "ROLE_ESCALATION_FORBIDDEN");
        api.patch("/api/admin/users/" + other.id() + "/status").bearer(adminToken)
                .json(Json.object("status", "DISABLED")).send().expectError(403, "ROLE_ESCALATION_FORBIDDEN");
        api.delete("/api/admin/users/" + other.id()).bearer(adminToken).send()
                .expectError(403, "ROLE_ESCALATION_FORBIDDEN");

        loginResponse(other.email(), PASSWORD).expectStatus(200);
    }

    @Test
    void anAdminSetPasswordFollowsTheSignUpRule() {
        TestUser user = newUser("weak", "USER");
        String adminToken = newAdminToken();

        api.put("/api/admin/users/" + user.id()).bearer(adminToken)
                .json(Json.object("password", "a")).send().expectStatus(400);
        api.post("/api/admin/users").bearer(adminToken)
                .json(Json.object("email", uniqueEmail("weak-new"), "fullName", "Weak New",
                        "roles", List.of("USER"), "password", "short"))
                .send().expectStatus(400);
        // Blank still means "no password", as the create form has always allowed.
        api.post("/api/admin/users").bearer(adminToken)
                .json(Json.object("email", uniqueEmail("no-password"), "fullName", "No Password",
                        "roles", List.of("USER"), "password", ""))
                .send().expectStatus(201);
    }

    /**
     * A failed-login lockout used to be reported as DISABLED, so the dashboard's Unlock never
     * appeared, and enabling it again left the failure count at five for the next typo to trip.
     */
    @Test
    void aLockedOutAccountIsListedAsLockedAndEnablingItClearsTheLock() {
        TestUser user = newUser("lock-listed", "USER");
        for (int i = 0; i < 5; i++) {
            loginResponse(user.email(), "wrong-password").expectStatus(401);
        }
        String adminToken = newAdminToken();

        JsonNode listed = api.get("/api/admin/users").bearer(adminToken)
                .query("status", "LOCKED").query("search", user.email()).send().expectStatus(200)
                .data().path("content");
        assertThat(listed.size()).isEqualTo(1);
        assertThat(listed.get(0).path("status").asText()).isEqualTo("LOCKED");
        assertThat(listed.get(0).path("lockedUntil").isNull()).isFalse();

        api.patch("/api/admin/users/" + user.id() + "/status").bearer(adminToken)
                .json(Json.object("status", "ACTIVE")).send().expectStatus(200);

        assertThat(jdbc.queryForObject("select failed_logins from users where id = ?", Integer.class, user.id()))
                .isZero();
        assertThat(jdbc.queryForObject("select locked_until is null from users where id = ?", Boolean.class,
                user.id())).isTrue();
        loginResponse(user.email(), PASSWORD).expectStatus(200);
    }

    private ApiClient.Response refresh(String refreshToken) {
        return api.post("/api/auth/refresh").json(Json.object("refreshToken", refreshToken)).send();
    }

    private int liveRefreshTokens(UUID userId) {
        return jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Integer.class, userId);
    }
}
