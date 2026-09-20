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
 * Role management through /api/admin/users. ADMIN holds user:manage too, so the SUPER_ADMIN tier
 * is protected in the service: only a SUPER_ADMIN may grant or revoke SUPER_ADMIN
 * (ROLE_ESCALATION_FORBIDDEN), and nobody may change their own roles (SELF_ROLE_CHANGE_FORBIDDEN).
 * Every refusal is checked against the stored roles, not only the status code.
 */
class AdminUserRolesTest extends AbstractIntegrationTest {

    @Test
    void anAdminCannotGrantItselfSuperAdmin() {
        TestUser admin = newUser("admin", "ADMIN");

        updateRoles(login(admin.email()), admin.id(), "ADMIN", "SUPER_ADMIN")
                .expectError(403, "ROLE_ESCALATION_FORBIDDEN");

        assertThat(rolesInDb(admin.id())).containsExactly("ADMIN");
    }

    @Test
    void anAdminCannotGrantSuperAdminToAnotherUser() {
        TestUser target = newUser("target", "USER");

        updateRoles(newAdminToken(), target.id(), "USER", "SUPER_ADMIN")
                .expectError(403, "ROLE_ESCALATION_FORBIDDEN");

        assertThat(rolesInDb(target.id())).containsExactly("USER");
    }

    @Test
    void anAdminCannotRevokeSuperAdmin() {
        TestUser superAdmin = newUser("super", "SUPER_ADMIN");

        updateRoles(newAdminToken(), superAdmin.id(), "ADMIN")
                .expectError(403, "ROLE_ESCALATION_FORBIDDEN");

        assertThat(rolesInDb(superAdmin.id())).containsExactly("SUPER_ADMIN");
    }

    /** Creating an account that holds SUPER_ADMIN is granting it, with a password the ADMIN chose. */
    @Test
    void anAdminCannotCreateASuperAdmin() {
        String email = uniqueEmail("new-super");

        api.post("/api/admin/users").bearer(newAdminToken())
                .json(Json.object("email", email, "fullName", "New Super", "roles", List.of("SUPER_ADMIN"),
                        "password", PASSWORD))
                .send().expectError(403, "ROLE_ESCALATION_FORBIDDEN");

        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email))
                .as("accounts created").isZero();
    }

    /** The positive control: the rule restricts who may grant SUPER_ADMIN, it does not forbid it. */
    @Test
    void aSuperAdminCanGrantSuperAdminToAnotherUser() {
        TestUser superAdmin = newUser("super", "SUPER_ADMIN");
        TestUser target = newUser("target", "ADMIN");

        updateRoles(login(superAdmin.email()), target.id(), "ADMIN", "SUPER_ADMIN").expectStatus(200);

        assertThat(rolesInDb(target.id())).containsExactlyInAnyOrder("ADMIN", "SUPER_ADMIN");
    }

    @Test
    void anAdminCannotChangeItsOwnRoles() {
        TestUser admin = newUser("admin", "ADMIN");

        updateRoles(login(admin.email()), admin.id(), "ADMIN", "TUTOR")
                .expectError(403, "SELF_ROLE_CHANGE_FORBIDDEN");

        assertThat(rolesInDb(admin.id())).containsExactly("ADMIN");
    }

    /** SUPER_ADMIN passes the escalation rule, so this isolates the self rule. */
    @Test
    void aSuperAdminCannotChangeItsOwnRoles() {
        TestUser superAdmin = newUser("super", "SUPER_ADMIN");

        updateRoles(login(superAdmin.email()), superAdmin.id(), "ADMIN")
                .expectError(403, "SELF_ROLE_CHANGE_FORBIDDEN");

        assertThat(rolesInDb(superAdmin.id())).containsExactly("SUPER_ADMIN");
    }

    /**
     * The dashboard's user form resends the current role set with every save, including when an
     * admin edits their own name. An unchanged set is not a role change and must not be refused.
     */
    @Test
    void editingOwnProfileWhileResendingUnchangedRolesIsAllowed() {
        TestUser admin = newUser("admin", "ADMIN");

        api.put("/api/admin/users/" + admin.id()).bearer(login(admin.email()))
                .json(Json.object("fullName", "Renamed Admin", "roles", List.of("ADMIN")))
                .send().expectStatus(200);

        assertThat(jdbc.queryForObject("select first_name from users where id = ?", String.class, admin.id()))
                .isEqualTo("Renamed");
        assertThat(rolesInDb(admin.id())).containsExactly("ADMIN");
    }

    /**
     * The roles an account is created with used to exist only in the response: they were added to
     * the detached instance after save() had merged it. Re-read three ways: the table, the admin
     * list the dashboard shows, and the account's own login.
     */
    @Test
    void aCreatedUserKeepsTheRolesItWasCreatedWith() {
        String adminToken = newAdminToken();
        String email = uniqueEmail("staff");

        JsonNode created = api.post("/api/admin/users").bearer(adminToken)
                .json(Json.object("email", email, "fullName", "Staff Member", "roles", List.of("TUTOR"),
                        "password", PASSWORD))
                .send().expectStatus(201).data();
        UUID id = UUID.fromString(created.path("id").asText());
        assertThat(texts(created.path("roles"))).containsExactly("TUTOR");
        assertThat(created.path("createdAt").isNull() || created.path("createdAt").isMissingNode())
                .as("createdAt in the create response").isFalse();

        assertThat(rolesInDb(id)).containsExactly("TUTOR");

        JsonNode listed = api.get("/api/admin/users").bearer(adminToken).query("search", email)
                .send().expectStatus(200).data().path("content");
        assertThat(listed.size()).isEqualTo(1);
        assertThat(texts(listed.get(0).path("roles"))).containsExactly("TUTOR");

        JsonNode ownLogin = loginResponse(email, PASSWORD).expectStatus(200).data();
        assertThat(texts(ownLogin.path("user").path("roles"))).containsExactly("TUTOR");
    }

    private ApiClient.Response updateRoles(String callerToken, UUID targetId, String... roles) {
        return api.put("/api/admin/users/" + targetId).bearer(callerToken)
                .json(Json.object("roles", List.of(roles)))
                .send();
    }
}
