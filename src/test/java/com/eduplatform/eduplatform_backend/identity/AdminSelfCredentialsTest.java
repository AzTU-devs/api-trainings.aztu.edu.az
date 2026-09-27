package com.eduplatform.eduplatform_backend.identity;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An administrator's own email and password are not the Users page's to change on an access token
 * alone. The self rules covered roles, status and deletion but not the credentials, so any ADMIN
 * or SUPER_ADMIN could set a new email and password for themselves with no current password — and
 * a borrowed or copied 15-minute token became the account for good.
 */
class AdminSelfCredentialsTest extends AbstractIntegrationTest {

    @Test
    void anAdminCannotSetTheirOwnPasswordFromTheUsersPage() {
        TestUser admin = newUser("self-pw", "ADMIN");
        String token = login(admin.email());

        edit(token, admin, Json.object("password", "Takeover12345"))
                .expectError(403, "SELF_PASSWORD_CHANGE_FORBIDDEN");

        loginResponse(admin.email(), "Takeover12345").expectError(401, "INVALID_CREDENTIALS");
        loginResponse(admin.email(), PASSWORD).expectStatus(200);
    }

    @Test
    void anAdminChangesTheirOwnEmailOnlyWithTheirCurrentPassword() {
        TestUser admin = newUser("self-email", "SUPER_ADMIN");
        String token = login(admin.email());
        String newEmail = uniqueEmail("self-email-new");

        edit(token, admin, Json.object("email", newEmail)).expectError(403, "CURRENT_PASSWORD_REQUIRED");
        edit(token, admin, Json.object("email", newEmail, "currentPassword", "not-my-password"))
                .expectError(400, "INVALID_CURRENT_PASSWORD");
        assertThat(jdbc.queryForObject("select email from users where id = ?", String.class, admin.id()))
                .isEqualTo(admin.email());

        edit(token, admin, Json.object("email", newEmail, "currentPassword", PASSWORD)).expectStatus(200);
        loginResponse(newEmail, PASSWORD).expectStatus(200);
        // Every access token carries the email, so the old ones are stale now.
        api.get("/api/auth/me").bearer(token).send().expectError(401, "TOKEN_STALE");
    }

    /** What the dashboard's own-row Edit sends: the unchanged email, a name, no password. */
    @Test
    void anAdminStillEditsTheirOwnNameAndPhone() {
        TestUser admin = newUser("self-name", "ADMIN");
        String token = login(admin.email());

        edit(token, admin, Json.object("email", admin.email().toUpperCase(), "fullName", "Renamed Admin",
                "phone", "+994 50 123 45 67", "roles", List.of("ADMIN"))).expectStatus(200);

        assertThat(jdbc.queryForObject("select first_name from users where id = ?", String.class, admin.id()))
                .isEqualTo("Renamed");
    }

    /** The rule is about one's own account; managing someone else's is unchanged. */
    @Test
    void aSuperAdminStillSetsAnotherAccountsEmailAndPassword() {
        TestUser user = newUser("managed", "USER");
        String superToken = login(newUser("super", "SUPER_ADMIN").email());
        String newEmail = uniqueEmail("managed-new");

        api.put("/api/admin/users/" + user.id()).bearer(superToken)
                .json(Json.object("email", newEmail, "password", "Temporary12345")).send().expectStatus(200);

        loginResponse(newEmail, "Temporary12345").expectStatus(200);
    }

    private ApiClient.Response edit(String token, TestUser target, Map<String, Object> body) {
        return api.put("/api/admin/users/" + target.id()).bearer(token).json(body).send();
    }
}
