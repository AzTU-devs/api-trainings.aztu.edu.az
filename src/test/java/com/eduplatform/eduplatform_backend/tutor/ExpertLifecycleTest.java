package com.eduplatform.eduplatform_backend.tutor;

import com.eduplatform.eduplatform_backend.common.security.TokenHasher;
import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An expert's account from sign-up to removal: what sign-up stores, what the TUTOR role and the
 * approval decisions do, and what deleting the account may and may not leave behind.
 */
class ExpertLifecycleTest extends AbstractIntegrationTest {

    /**
     * Deleting an expert's account left their profile, and the courses naming it, pointing at a
     * user no read could load: the course lists, the catalogue and the Tutors page all answered 500.
     */
    @Test
    void anExpertOnACourseCannotBeDeletedButOneWithNothingCan() {
        TestTutor teaching = newApprovedTutor("teaching");
        createCourse(login(teaching.email()), courseRequest("keeps-its-tutor", true));
        TestTutor idle = newApprovedTutor("idle");
        String superToken = login(newUser("super", "SUPER_ADMIN").email());

        api.delete("/api/admin/users/" + teaching.userId()).bearer(superToken).send()
                .expectError(409, "USER_HAS_COURSES");
        api.get("/api/portal/tutor/admin").bearer(superToken).query("status", "APPROVED").send().expectStatus(200);

        api.delete("/api/admin/users/" + idle.userId()).bearer(superToken).send().expectStatus(204);
        assertThat(jdbc.queryForObject("select deleted_at is not null from tutor_profiles where id = ?",
                Boolean.class, idle.profileId())).as("the profile goes with the account").isTrue();
        api.get("/api/portal/tutor/admin").bearer(superToken).query("status", "APPROVED").query("size", 100)
                .send().expectStatus(200);
        api.get("/api/public/tutors/" + idle.profileId()).send().expectStatus(404);
    }

    /** Granting TUTOR from the Users page used to create no profile, so the account could not teach. */
    @Test
    void anAccountCreatedWithTheTutorRoleCanTeachAtOnce() {
        String email = uniqueEmail("granted-expert");
        api.post("/api/admin/users").bearer(newAdminToken())
                .json(Json.object("email", email, "fullName", "Granted Expert", "roles", List.of("TUTOR"),
                        "password", PASSWORD))
                .send().expectStatus(201);
        String token = login(email);

        JsonNode profile = api.get("/api/portal/tutor/me").bearer(token).send().expectStatus(200).data();
        assertThat(profile.path("approvalStatus").asText()).isEqualTo("APPROVED");
        createCourse(token, courseRequest("first-course", true));
    }

    /** Rejecting an approved expert used to leave them TUTOR, still editing and submitting courses. */
    @Test
    void withdrawingAnApprovalTakesTheTutorRoleAndTheEditingRightAway() {
        TestTutor tutor = newApprovedTutor("withdrawn");
        String tutorToken = login(tutor.email());
        CourseRef course = createCourse(tutorToken, courseRequest("orphaned", true));
        String adminToken = newAdminToken();

        decide(adminToken, tutor.profileId(), "REJECTED").expectStatus(200);

        assertThat(rolesInDb(tutor.userId())).doesNotContain("TUTOR");
        api.patch("/api/portal/courses/" + course.id()).bearer(tutorToken)
                .json(Json.object("title", "Still mine?")).send().expectStatus(401);
        decide(adminToken, tutor.profileId(), "REJECTED").expectError(409, "INVALID_TUTOR_TRANSITION");

        // Re-approving gives the role back, as the dashboard's re-approve expects.
        decide(adminToken, tutor.profileId(), "APPROVED").expectStatus(200);
        assertThat(rolesInDb(tutor.userId())).contains("TUTOR");
    }

    /** A rejected applicant could neither edit, nor apply again, nor sign up again. */
    @Test
    void aRejectedApplicantResubmitsAndSeesWhy() {
        TestUser user = newUser("applicant", "USER");
        UUID profileId = UUID.randomUUID();
        jdbc.update("""
                insert into tutor_profiles (id, user_id, headline, approval_status)
                values (?, ?, 'First try', 'PENDING')
                """, profileId, user.id());
        decide(newAdminToken(), profileId, "REJECTED").expectStatus(200);
        String token = login(user.email());

        JsonNode mine = api.get("/api/portal/tutor/me").bearer(token).send().expectStatus(200).data();
        assertThat(mine.path("rejectionReason").asText()).isEqualTo("integration test");

        JsonNode resubmitted = api.post("/api/portal/tutor/me/resubmit").bearer(token)
                .json(Json.object("headline", "Second try")).send().expectStatus(200).data();
        assertThat(resubmitted.path("approvalStatus").asText()).isEqualTo("PENDING");
        assertThat(resubmitted.path("headline").asText()).isEqualTo("Second try");
        api.post("/api/portal/tutor/me/resubmit").bearer(token).json(Json.object()).send()
                .expectError(409, "TUTOR_NOT_REJECTED");
    }

    /** The decision reaches the applicant: an in-app notification (and a mail, logged in tests). */
    @Test
    void anApprovalIsNotifiedToTheExpert() {
        TestUser user = newUser("notified", "USER");
        UUID profileId = UUID.randomUUID();
        jdbc.update("insert into tutor_profiles (id, user_id, approval_status) values (?, ?, 'PENDING')",
                profileId, user.id());

        decide(newAdminToken(), profileId, "APPROVED").expectStatus(200);

        assertThat(jdbc.queryForObject(
                "select count(*) from notifications where user_id = ? and template_code = 'tutor.approved'",
                Integer.class, user.id())).isEqualTo(1);
    }

    /** Sign-up stored javascript: links and a 500-year career, which the public page then rendered. */
    @Test
    void expertSignUpValidatesLinksAndYears() {
        Map<String, Object> request = signUp(uniqueEmail("bad-links"));
        request.put("websiteUrl", "javascript:alert(document.domain)");
        start(request).expectStatus(400);

        request = signUp(uniqueEmail("bad-years"));
        request.put("yearsExperience", 500);
        start(request).expectStatus(400);

        request = signUp(uniqueEmail("creds"));
        request.put("linkedinUrl", "https://linkedin.com@evil.example");
        start(request).expectStatus(400);
    }

    /** The account used to be created in English and "Unverified", although the code proved the inbox. */
    @Test
    void anExpertWhoSignsUpInAzerbaijaniGetsAVerifiedAzerbaijaniAccount() {
        String email = uniqueEmail("az-expert");
        start(signUp(email)).expectStatus(202);
        String otp = "135790";
        jdbc.update("update tutor_registration_otps set otp_hash = ? where email = ? and consumed_at is null",
                TokenHasher.sha256Hex(otp), email);

        api.post("/api/auth/register/tutor/verify").json(Json.object("email", email, "otp", otp)).send()
                .expectStatus(201);

        Map<String, Object> row = jdbc.queryForMap(
                "select locale, email_verified_at is not null as verified from users where email = ?", email);
        assertThat(row.get("locale")).isEqualTo("az");
        assertThat(row.get("verified")).isEqualTo(true);
    }

    /** Every resend mails the address again; one a minute keeps an address from being mail-bombed. */
    @Test
    void aSecondCodeForTheSameAddressWithinAMinuteIsRefused() {
        String email = uniqueEmail("resend");
        start(signUp(email)).expectStatus(202);
        ApiClient.Response again = start(signUp(email)).expectError(429, "OTP_RESEND_TOO_SOON");
        assertThat(again.header("Retry-After")).isPresent();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private ApiClient.Response decide(String adminToken, UUID profileId, String decision) {
        return api.post("/api/portal/tutor/admin/" + profileId + "/decision").bearer(adminToken)
                .json(Json.object("decision", decision, "note", "integration test")).send();
    }

    private ApiClient.Response start(Map<String, Object> request) {
        return api.post("/api/auth/register/tutor/start").json(request).send();
    }

    private Map<String, Object> signUp(String email) {
        return Json.object("email", email, "password", PASSWORD, "firstName", "Expert", "lastName", "Signup",
                "locale", "az", "headline", "Teaches things", "categoryIds", List.of(anyCategory()));
    }

    private UUID anyCategory() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into categories (id, slug, name, is_active) values (?, ?, 'Test category', true)",
                id, unique("cat"));
        return id;
    }
}
