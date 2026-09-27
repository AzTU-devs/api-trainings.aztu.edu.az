package com.eduplatform.eduplatform_backend.identity;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A signed-in user managing their own account: password, profile fields, and what errors echo. */
class AccountSelfServiceTest extends AbstractIntegrationTest {

    @Test
    void changingThePasswordNeedsTheCurrentOneAndSignsOutEveryOtherSession() {
        TestUser user = newUser("changer", "USER");
        JsonNode here = loginResponse(user.email(), PASSWORD).expectStatus(200).data();
        JsonNode elsewhere = loginResponse(user.email(), PASSWORD).expectStatus(200).data();
        String access = here.path("accessToken").asText();

        change(access, "not-my-password", "NewPassword123", null)
                .expectError(400, "INVALID_CURRENT_PASSWORD");
        change(access, PASSWORD, "weak", null).expectStatus(400);

        change(access, PASSWORD, "NewPassword123", here.path("refreshToken").asText()).expectStatus(204);

        loginResponse(user.email(), PASSWORD).expectError(401, "INVALID_CREDENTIALS");
        loginResponse(user.email(), "NewPassword123").expectStatus(200);
        refresh(elsewhere.path("refreshToken").asText()).expectStatus(401);
        refresh(here.path("refreshToken").asText()).expectStatus(200);
    }

    @Test
    void theProfileRefusesAnUnknownLocaleABlankNameAndAMalformedPhone() {
        String token = login(newUser("profile", "USER").email());

        updateMe(token, Json.object("locale", "xx")).expectStatus(400);
        updateMe(token, Json.object("firstName", "   ")).expectStatus(400);
        updateMe(token, Json.object("phone", "abc")).expectStatus(400);

        JsonNode me = updateMe(token, Json.object("locale", "az", "phone", "+994 (50) 123-45-67"))
                .expectStatus(200).data();
        assertThat(me.path("locale").asText()).isEqualTo("az");
    }

    /**
     * Changing the password revoked the other sessions' refresh tokens but left their access tokens
     * working, so whoever held the old session kept API access for up to 15 minutes. The session
     * that made the change carries on through its refresh token.
     */
    @Test
    void changingThePasswordMakesEveryAccessTokenStale() {
        TestUser user = newUser("stale-after-change", "USER");
        JsonNode here = loginResponse(user.email(), PASSWORD).expectStatus(200).data();
        String elsewhere = login(user.email());
        api.get("/api/auth/me").bearer(elsewhere).send().expectStatus(200);

        change(here.path("accessToken").asText(), PASSWORD, "NewPassword123", here.path("refreshToken").asText())
                .expectStatus(204);

        api.get("/api/auth/me").bearer(elsewhere).send().expectError(401, "TOKEN_STALE");
        String renewed = refresh(here.path("refreshToken").asText()).expectStatus(200).data()
                .path("accessToken").asText();
        api.get("/api/auth/me").bearer(renewed).send().expectStatus(200);
    }

    /** The phone pattern counted characters, not digits, so seven dashes were a phone number. */
    @Test
    void aPhoneNumberNeedsDigits() {
        TestUser user = newUser("phone-digits", "USER");
        String token = login(user.email());

        updateMe(token, Json.object("phone", "-------")).expectStatus(400);
        updateMe(token, Json.object("phone", "(  )  -  -")).expectStatus(400);
        updateMe(token, Json.object("phone", "12-34-56")).expectStatus(400);
        api.put("/api/admin/users/" + user.id()).bearer(newAdminToken())
                .json(Json.object("phone", "- - - - - - -")).send().expectStatus(400);

        updateMe(token, Json.object("phone", "+994501234567")).expectStatus(200);
        updateMe(token, Json.object("phone", "")).expectStatus(200);
    }

    /** V21 clears the digitless numbers the old pattern let in, and leaves real ones alone. */
    @Test
    void theRepairClearsPhoneNumbersWithoutDigits() {
        TestUser dashes = newUser("phone-dashes", "USER");
        TestUser real = newUser("phone-real", "USER");
        jdbc.update("update users set phone = '-------' where id = ?", dashes.id());
        jdbc.update("update users set phone = '+994 (12) 555-55-55' where id = ?", real.id());

        rerunMigration("V21__deleted_accounts_and_input_rules.sql");

        assertThat(jdbc.queryForObject("select phone from users where id = ?", String.class, dashes.id())).isNull();
        assertThat(jdbc.queryForObject("select phone from users where id = ?", String.class, real.id()))
                .isEqualTo("+994 (12) 555-55-55");
    }

    /** A validation error used to echo the submitted value — for a weak password, the password. */
    @Test
    void aValidationErrorDoesNotEchoTheSubmittedPassword() {
        ApiClient.Response response = api.post("/api/auth/register")
                .json(Json.object("email", uniqueEmail("echo"), "password", "weakpass",
                        "firstName", "Echo", "lastName", "Test"))
                .send().expectStatus(400);

        assertThat(response.body()).doesNotContain("weakpass");
    }

    private ApiClient.Response change(String access, String current, String next, String refreshToken) {
        return api.post("/api/auth/password/change").bearer(access)
                .json(Json.object("currentPassword", current, "newPassword", next, "refreshToken", refreshToken))
                .send();
    }

    private ApiClient.Response updateMe(String token, java.util.Map<String, Object> body) {
        return api.put("/api/auth/me").bearer(token).json(body).send();
    }

    private ApiClient.Response refresh(String refreshToken) {
        return api.post("/api/auth/refresh").json(Json.object("refreshToken", refreshToken)).send();
    }
}
