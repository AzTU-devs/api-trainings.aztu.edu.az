package com.eduplatform.eduplatform_backend.tutor;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.eduplatform.eduplatform_backend.support.TestFiles;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The expert profile details (V13) and the two ways of editing them: the expert's own
 * {@code PATCH /api/portal/tutor/me} behind {@code tutor:manage_self}, and an admin's
 * {@code PATCH /api/admin/tutors/{tutorId}} behind the new {@code tutor:manage}.
 *
 * <p>What is pinned here is what a mistake would put in public or lock out an admin: V13 has to
 * grant {@code tutor:manage} to SUPER_ADMIN by name, since V2's grant-everything was a one-time
 * cross join; neither edit may move a profile's approval; an absent field is left alone; links
 * and the ORCID iD are checked on the server; the avatar has to be an image the expert or the
 * editing admin uploaded; and the anonymous media endpoint serves it only while the expert is
 * APPROVED.
 */
class ExpertProfileEditTest extends AbstractIntegrationTest {

    private static final String ME = "/api/portal/tutor/me";

    // ── who may edit ────────────────────────────────────────────────────

    @Test
    void anExpertEditsTheirOwnProfile() {
        TestTutor tutor = newApprovedTutor("expert");
        String token = login(tutor.email());

        JsonNode dto = patchMe(token, fullDetails()).expectStatus(200).data();

        assertThat(dto.path("id").asText()).isEqualTo(tutor.profileId().toString());
        assertThat(dto.path("headline").asText()).isEqualTo("Professor of distributed systems");
        assertThat(dto.path("academicTitle").asText()).isEqualTo("Dosent");
        assertThat(dto.path("department").asText()).isEqualTo("Faculty of Information Technology");
        assertThat(dto.path("education").asText()).isEqualTo("PhD, AzTU, 2012\nMSc, AzTU, 2008");
        assertThat(dto.path("certifications").asText()).isEqualTo("AWS Solutions Architect\nCKA");
        assertThat(dto.path("languages").asText()).isEqualTo("Azerbaijani, English, Russian");
        assertThat(dto.path("googleScholarUrl").asText()).isEqualTo("https://scholar.google.com/citations?user=abc");
        assertThat(dto.path("researchGateUrl").asText()).isEqualTo("https://www.researchgate.net/profile/X");
        assertThat(dto.path("githubUrl").asText()).isEqualTo("https://github.com/aztu");
        assertThat(dto.path("orcid").asText()).isEqualTo("0000-0002-1825-0097");
        assertThat(dto.path("yearsExperience").asInt()).isEqualTo(12);

        Map<String, Object> row = profileRow(tutor.profileId());
        assertThat(row.get("academic_title")).isEqualTo("Dosent");
        assertThat(row.get("education")).isEqualTo("PhD, AzTU, 2012\nMSc, AzTU, 2008");
        assertThat(row.get("orcid")).isEqualTo("0000-0002-1825-0097");
        assertThat(auditedUpdates(tutor.profileId(), tutor.userId())).isEqualTo(1);
    }

    /** The only way a tutor reaches another expert's profile is the admin endpoint, which refuses them. */
    @Test
    void anExpertCannotEditAnotherExpertsProfile() {
        TestTutor victim = newApprovedTutor("victim");
        String attacker = login(newApprovedTutor("attacker").email());

        patchAdmin(attacker, victim.profileId(), Json.object("headline", "Defaced"))
                .expectStatus(403);

        assertThat(profileRow(victim.profileId()).get("headline")).isEqualTo("Integration-test tutor");
    }

    /** A pending applicant has only the USER role, so not tutor:manage_self, and cannot publish a profile early. */
    @Test
    void anApplicantWithoutTheTutorRoleCannotUseEitherEndpoint() {
        Applicant applicant = newApplicant("applicant");
        String token = login(applicant.email());

        patchMe(token, Json.object("headline", "Let me in")).expectStatus(403);
        patchAdmin(token, applicant.profileId(), Json.object("headline", "Let me in")).expectStatus(403);

        assertThat(profileRow(applicant.profileId()).get("headline")).isEqualTo("Pending applicant");
    }

    @Test
    void anAdminEditsAnyExpertsProfile() {
        TestTutor tutor = newApprovedTutor("expert");
        TestUser admin = newUser("admin", "ADMIN");

        JsonNode dto = patchAdmin(login(admin.email()), tutor.profileId(),
                Json.object("academicTitle", "Associate Professor", "department", "Faculty of Energy"))
                .expectStatus(200).data();

        assertThat(dto.path("academicTitle").asText()).isEqualTo("Associate Professor");
        assertThat(profileRow(tutor.profileId()).get("department")).isEqualTo("Faculty of Energy");
        assertThat(auditedUpdates(tutor.profileId(), admin.id())).isEqualTo(1);
    }

    /**
     * SUPER_ADMIN got every permission from V2's cross join, which ran before tutor:manage existed;
     * this passing means V13 granted it by name.
     */
    @Test
    void aSuperAdminEditsAnyExpertsProfile() {
        TestTutor tutor = newApprovedTutor("expert");
        TestUser superAdmin = newUser("super", "SUPER_ADMIN");

        patchAdmin(login(superAdmin.email()), tutor.profileId(), Json.object("languages", "Azerbaijani"))
                .expectStatus(200);

        assertThat(profileRow(tutor.profileId()).get("languages")).isEqualTo("Azerbaijani");
        assertThat(auditedUpdates(tutor.profileId(), superAdmin.id())).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                select r.code from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code = 'tutor:manage'
                """, String.class)).containsExactlyInAnyOrder("ADMIN", "SUPER_ADMIN");
    }

    @Test
    void anAdminCanEditAPendingApplicantsProfile() {
        Applicant applicant = newApplicant("applicant");

        patchAdmin(newAdminToken(), applicant.profileId(), Json.object("headline", "Corrected by staff"))
                .expectStatus(200);

        assertThat(profileRow(applicant.profileId()).get("headline")).isEqualTo("Corrected by staff");
    }

    @Test
    void editingAnUnknownProfileIsNotFound() {
        patchAdmin(newAdminToken(), UUID.randomUUID(), Json.object("headline", "Nobody"))
                .expectError(404, "TUTOR_PROFILE_NOT_FOUND");
    }

    // ── approval cannot move ────────────────────────────────────────────

    @Test
    void anExpertCannotChangeTheirOwnApprovalStatus() {
        TestTutor tutor = newApprovedTutor("expert");

        JsonNode dto = patchMe(login(tutor.email()),
                Json.object("approvalStatus", "SUSPENDED", "headline", "Still approved"))
                .expectStatus(200).data();

        assertThat(dto.path("approvalStatus").asText()).isEqualTo("APPROVED");
        assertThat(profileRow(tutor.profileId()).get("approval_status")).isEqualTo("APPROVED");
    }

    /** Going live is the decision endpoint's alone, so an edit cannot approve an applicant on the side. */
    @Test
    void anAdminEditCannotApproveAnApplicant() {
        Applicant applicant = newApplicant("applicant");

        JsonNode dto = patchAdmin(newAdminToken(), applicant.profileId(),
                Json.object("approvalStatus", "APPROVED", "approvedAt", "2026-01-01T00:00:00Z",
                        "headline", "Edited, not approved"))
                .expectStatus(200).data();

        assertThat(dto.path("approvalStatus").asText()).isEqualTo("PENDING");
        Map<String, Object> row = profileRow(applicant.profileId());
        assertThat(row.get("approval_status")).isEqualTo("PENDING");
        assertThat(row.get("approved_at")).isNull();
        api.get("/api/public/tutors/" + applicant.profileId()).send().expectError(404, "TUTOR_NOT_FOUND");
    }

    // ── partial update ──────────────────────────────────────────────────

    @Test
    void aFieldLeftOutOfTheBodyIsLeftUnchanged() {
        TestTutor tutor = newApprovedTutor("expert");
        String token = login(tutor.email());
        UUID category = newCategory();
        Map<String, Object> details = fullDetails();
        details.put("expertiseCategoryIds", List.of(category));
        patchMe(token, details).expectStatus(200);

        JsonNode dto = patchMe(token, Json.object("department", "Faculty of Mechanics")).expectStatus(200).data();

        assertThat(dto.path("department").asText()).isEqualTo("Faculty of Mechanics");
        assertThat(dto.path("headline").asText()).isEqualTo("Professor of distributed systems");
        assertThat(dto.path("academicTitle").asText()).isEqualTo("Dosent");
        assertThat(dto.path("education").asText()).isEqualTo("PhD, AzTU, 2012\nMSc, AzTU, 2008");
        assertThat(dto.path("orcid").asText()).isEqualTo("0000-0002-1825-0097");
        assertThat(dto.path("githubUrl").asText()).isEqualTo("https://github.com/aztu");
        assertThat(dto.path("yearsExperience").asInt()).isEqualTo(12);
        assertThat(Json.texts(dto.path("expertiseCategoryIds"))).containsExactly(category.toString());
    }

    /** null clears a field, and so does the blank string an emptied form input sends. */
    @Test
    void nullOrBlankClearsAField() {
        TestTutor tutor = newApprovedTutor("expert");
        String token = login(tutor.email());
        patchMe(token, fullDetails()).expectStatus(200);

        JsonNode dto = patchMe(token, Json.object("githubUrl", null, "academicTitle", "   ", "yearsExperience", null))
                .expectStatus(200).data();

        assertThat(dto.path("githubUrl").isNull()).as("githubUrl in %s", dto).isTrue();
        assertThat(dto.path("academicTitle").isNull()).as("academicTitle in %s", dto).isTrue();
        assertThat(dto.path("yearsExperience").isNull()).as("yearsExperience in %s", dto).isTrue();
        assertThat(dto.path("department").asText()).isEqualTo("Faculty of Information Technology");
        Map<String, Object> row = profileRow(tutor.profileId());
        assertThat(row.get("github_url")).isNull();
        assertThat(row.get("academic_title")).isNull();
    }

    @Test
    void expertiseCanBeReplacedButNotEmptied() {
        TestTutor tutor = newApprovedTutor("expert");
        String token = login(tutor.email());
        UUID first = newCategory();
        UUID second = newCategory();
        patchMe(token, Json.object("expertiseCategoryIds", List.of(first))).expectStatus(200);

        JsonNode dto = patchMe(token, Json.object("expertiseCategoryIds", List.of(second))).expectStatus(200).data();
        assertThat(Json.texts(dto.path("expertiseCategoryIds"))).containsExactly(second.toString());

        expectFieldError(patchMe(token, Json.object("expertiseCategoryIds", List.of())), "expertiseCategoryIds");
        patchMe(token, Json.object("expertiseCategoryIds", List.of(UUID.randomUUID())))
                .expectError(400, "INVALID_CATEGORY");
        assertThat(jdbc.queryForList("select category_id from tutor_expertises where tutor_id = ?", UUID.class,
                tutor.profileId())).containsExactly(second);
    }

    // ── server-side validation ──────────────────────────────────────────

    @Test
    void aLinkThatIsNotAnHttpUrlIsRejected() {
        TestTutor tutor = newApprovedTutor("expert");
        String token = login(tutor.email());

        expectFieldError(patchMe(token, Json.object("websiteUrl", "not a url")), "websiteUrl");
        expectFieldError(patchMe(token, Json.object("githubUrl", "javascript:alert(1)")), "githubUrl");
        expectFieldError(patchMe(token, Json.object("linkedinUrl", "ftp://linkedin.com/in/x")), "linkedinUrl");
        // Reads as Google Scholar and goes to evil.example.
        expectFieldError(patchMe(token, Json.object("googleScholarUrl", "https://scholar.google.com@evil.example/")),
                "googleScholarUrl");
        expectFieldError(patchMe(token, Json.object("researchGateUrl", "www.researchgate.net/profile/X")),
                "researchGateUrl");

        Map<String, Object> row = profileRow(tutor.profileId());
        assertThat(row.get("website_url")).isNull();
        assertThat(row.get("github_url")).isNull();
    }

    @Test
    void anOrcidThatIsNotABareIdIsRejected() {
        TestTutor tutor = newApprovedTutor("expert");
        String token = login(tutor.email());

        expectFieldError(patchMe(token, Json.object("orcid", "0000-0002-1825")), "orcid");
        expectFieldError(patchMe(token, Json.object("orcid", "https://orcid.org/0000-0002-1825-0097")), "orcid");
        expectFieldError(patchMe(token, Json.object("orcid", "0000-0002-1825-009Y")), "orcid");
        assertThat(profileRow(tutor.profileId()).get("orcid")).isNull();

        // The check digit X is upper-case by definition; a typed lower-case one is the same iD.
        JsonNode dto = patchMe(token, Json.object("orcid", " 0000-0002-1694-233x ")).expectStatus(200).data();
        assertThat(dto.path("orcid").asText()).isEqualTo("0000-0002-1694-233X");
    }

    @Test
    void theAdminEndpointValidatesTheSameWay() {
        TestTutor tutor = newApprovedTutor("expert");

        expectFieldError(patchAdmin(newAdminToken(), tutor.profileId(), Json.object("orcid", "orcid")), "orcid");
        expectFieldError(patchAdmin(newAdminToken(), tutor.profileId(), Json.object("websiteUrl", "aztu.edu.az")),
                "websiteUrl");
    }

    // ── avatar ──────────────────────────────────────────────────────────

    @Test
    void anExpertSetsAndRemovesTheirOwnAvatar() {
        TestTutor tutor = newApprovedTutor("expert");
        String token = login(tutor.email());
        UUID png = uploadMedia(token, "me.png", "image/png", TestFiles.png());

        JsonNode dto = patchMe(token, Json.object("avatarMediaId", png)).expectStatus(200).data();
        assertThat(dto.path("avatarMediaId").asText()).isEqualTo(png.toString());
        assertThat(dto.path("avatarUrl").asText()).isEqualTo("/api/public/media/" + png + "/content");
        assertThat(avatarInDb(tutor.profileId())).isEqualTo(png);

        JsonNode unrelated = patchMe(token, Json.object("headline", "Unrelated edit")).expectStatus(200).data();
        assertThat(unrelated.path("avatarMediaId").asText()).isEqualTo(png.toString());

        JsonNode removed = patchMe(token, Json.object("avatarMediaId", null)).expectStatus(200).data();
        assertThat(removed.path("avatarUrl").isNull()).as("avatarUrl in %s", removed).isTrue();
        assertThat(avatarInDb(tutor.profileId())).isNull();
    }

    /** An approved expert's avatar is public, so naming another user's upload would publish their file. */
    @Test
    void anotherUsersUploadCannotBeTheAvatar() {
        UUID someoneElses = uploadMedia(login(newUser("student", "USER").email()), "private.png", "image/png",
                TestFiles.png());
        TestTutor tutor = newApprovedTutor("expert");

        patchMe(login(tutor.email()), Json.object("avatarMediaId", someoneElses)).expectError(403, "MEDIA_FORBIDDEN");

        assertThat(avatarInDb(tutor.profileId())).isNull();
    }

    /** Unlike course media, an admin is not exempt from ownership: it must be their upload or the expert's. */
    @Test
    void anAdminMayUseTheirOwnOrTheExpertsUploadButNotAThirdPartys() {
        TestTutor tutor = newApprovedTutor("expert");
        String adminToken = newAdminToken();
        UUID adminsUpload = uploadMedia(adminToken, "portrait.png", "image/png", TestFiles.png());
        UUID expertsUpload = uploadMedia(login(tutor.email()), "mine.png", "image/png", TestFiles.png());
        UUID thirdParty = uploadMedia(login(newUser("student", "USER").email()), "other.png", "image/png",
                TestFiles.png());

        patchAdmin(adminToken, tutor.profileId(), Json.object("avatarMediaId", thirdParty))
                .expectError(403, "MEDIA_FORBIDDEN");
        patchAdmin(adminToken, tutor.profileId(), Json.object("avatarMediaId", expertsUpload)).expectStatus(200);
        assertThat(avatarInDb(tutor.profileId())).isEqualTo(expertsUpload);
        patchAdmin(adminToken, tutor.profileId(), Json.object("avatarMediaId", adminsUpload)).expectStatus(200);
        assertThat(avatarInDb(tutor.profileId())).isEqualTo(adminsUpload);

        // The expert resends the portrait the admin chose with an unrelated edit; they did not upload it,
        // but it is already theirs, so the save must not fail.
        patchMe(login(tutor.email()), Json.object("avatarMediaId", adminsUpload, "headline", "Thanks"))
                .expectStatus(200);
        assertThat(avatarInDb(tutor.profileId())).isEqualTo(adminsUpload);
    }

    @Test
    void aFileThatIsNotAnImageCannotBeTheAvatar() {
        TestTutor tutor = newApprovedTutor("expert");
        String token = login(tutor.email());
        UUID pdf = uploadMedia(token, "cv.pdf", "application/pdf", TestFiles.pdf());
        UUID mp4 = uploadMedia(token, "intro.mp4", "video/mp4", TestFiles.mp4());

        patchMe(token, Json.object("avatarMediaId", pdf)).expectError(422, "INVALID_MEDIA_FOR_FIELD");
        patchMe(token, Json.object("avatarMediaId", mp4)).expectError(422, "INVALID_MEDIA_FOR_FIELD");

        assertThat(avatarInDb(tutor.profileId())).isNull();
    }

    @Test
    void anImageThatHasNotFinishedUploadingCannotBeTheAvatar() {
        TestTutor tutor = newApprovedTutor("expert");
        UUID pending = UUID.randomUUID();
        jdbc.update("""
                insert into media_files (id, owner_user_id, storage, object_key, mime_type, byte_size, status, visibility)
                values (?, ?, 'LOCAL', ?, 'image/png', 1, 'PENDING', 'PRIVATE')
                """, pending, tutor.userId(), "pending/" + pending + ".png");

        patchMe(login(tutor.email()), Json.object("avatarMediaId", pending)).expectError(422, "INVALID_MEDIA_FOR_FIELD");
    }

    @Test
    void anUnknownAvatarIdIsNotFound() {
        TestTutor tutor = newApprovedTutor("expert");

        patchMe(login(tutor.email()), Json.object("avatarMediaId", UUID.randomUUID())).expectError(404, "MEDIA_NOT_FOUND");
    }

    // ── public read side ────────────────────────────────────────────────

    @Test
    void anApprovedExpertsAvatarIsServedAnonymously() {
        TestTutor tutor = newApprovedTutor("expert");
        String token = login(tutor.email());
        byte[] portrait = TestFiles.png();
        UUID png = uploadMedia(token, "me.png", "image/png", portrait);
        String avatarUrl = patchMe(token, Json.object("avatarMediaId", png)).expectStatus(200)
                .data().path("avatarUrl").asText();

        ApiClient.Response image = api.get(avatarUrl).send().expectStatus(200);

        assertThat(image.header("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("image/png"));
        assertThat(image.bytes()).isEqualTo(portrait);
    }

    /**
     * An applicant has not been put in front of the public, so neither has their photo: the
     * anonymous endpoint answers exactly as for an id that does not exist. The applicant can still
     * see it signed in, which is how the dashboard previews a portrait an admin uploaded for them.
     */
    @Test
    void aPendingApplicantsAvatarIsNotServedAnonymously() {
        Applicant applicant = newApplicant("applicant");
        String adminToken = newAdminToken();
        UUID png = uploadMedia(adminToken, "portrait.png", "image/png", TestFiles.png());
        String avatarUrl = patchAdmin(adminToken, applicant.profileId(), Json.object("avatarMediaId", png))
                .expectStatus(200).data().path("avatarUrl").asText();
        assertThat(avatarUrl).isEqualTo("/api/public/media/" + png + "/content");

        api.get(avatarUrl).send().expectError(404, "MEDIA_NOT_FOUND");

        api.get("/api/media/" + png + "/content").bearer(login(applicant.email())).send().expectStatus(200);
        api.get("/api/media/" + png + "/content").bearer(login(newUser("student", "USER").email())).send()
                .expectStatus(403);
    }

    /** Being public follows the profile's status, not the file: rejecting the expert takes the photo down. */
    @Test
    void anAvatarStopsBeingPublicWhenTheExpertIsRejected() {
        TestTutor tutor = newApprovedTutor("expert");
        String token = login(tutor.email());
        UUID png = uploadMedia(token, "me.png", "image/png", TestFiles.png());
        String avatarUrl = patchMe(token, Json.object("avatarMediaId", png)).expectStatus(200)
                .data().path("avatarUrl").asText();
        api.get(avatarUrl).send().expectStatus(200);

        api.post("/api/portal/tutor/admin/" + tutor.profileId() + "/decision").bearer(newAdminToken())
                .json(Json.object("decision", "REJECTED", "note", "Integration test"))
                .send().expectStatus(200);

        api.get(avatarUrl).send().expectError(404, "MEDIA_NOT_FOUND");
    }

    @Test
    void thePublicProfileCarriesTheNewDetailsAndTheAvatarUrl() {
        TestTutor tutor = newApprovedTutor("expert");
        String token = login(tutor.email());
        UUID png = uploadMedia(token, "me.png", "image/png", TestFiles.png());
        Map<String, Object> details = fullDetails();
        details.put("avatarMediaId", png);
        patchMe(token, details).expectStatus(200);

        JsonNode profile = api.get("/api/public/tutors/" + tutor.profileId()).send().expectStatus(200).data();

        assertThat(profile.path("avatarMediaId").asText()).isEqualTo(png.toString());
        assertThat(profile.path("avatarUrl").asText()).isEqualTo("/api/public/media/" + png + "/content");
        assertThat(profile.path("academicTitle").asText()).isEqualTo("Dosent");
        assertThat(profile.path("department").asText()).isEqualTo("Faculty of Information Technology");
        assertThat(profile.path("education").asText()).isEqualTo("PhD, AzTU, 2012\nMSc, AzTU, 2008");
        assertThat(profile.path("certifications").asText()).isEqualTo("AWS Solutions Architect\nCKA");
        assertThat(profile.path("languages").asText()).isEqualTo("Azerbaijani, English, Russian");
        assertThat(profile.path("googleScholarUrl").asText()).isEqualTo("https://scholar.google.com/citations?user=abc");
        assertThat(profile.path("researchGateUrl").asText()).isEqualTo("https://www.researchgate.net/profile/X");
        assertThat(profile.path("orcid").asText()).isEqualTo("0000-0002-1825-0097");
        assertThat(profile.path("githubUrl").asText()).isEqualTo("https://github.com/aztu");
    }

    @Test
    void aProfileWithoutAnAvatarHasANullAvatarUrl() {
        TestTutor tutor = newApprovedTutor("expert");

        JsonNode profile = api.get("/api/public/tutors/" + tutor.profileId()).send().expectStatus(200).data();

        assertThat(profile.has("avatarUrl")).as("avatarUrl present in %s", profile).isTrue();
        assertThat(profile.path("avatarUrl").isNull()).isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** A USER who has applied and is waiting: a PENDING profile, and no TUTOR role yet. */
    private record Applicant(UUID userId, String email, UUID profileId) {}

    private Applicant newApplicant(String label) {
        TestUser user = newUser(label, "USER");
        UUID profileId = UUID.randomUUID();
        jdbc.update("""
                insert into tutor_profiles (id, user_id, headline, approval_status)
                values (?, ?, 'Pending applicant', 'PENDING')
                """, profileId, user.id());
        return new Applicant(user.id(), user.email(), profileId);
    }

    private UUID newCategory() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into categories (id, slug, name) values (?, ?, 'Integration tests')", id, unique("category"));
        return id;
    }

    /** Every E1 field plus a few existing ones; mutable, so a test can add to it. */
    private static Map<String, Object> fullDetails() {
        return Json.object(
                "headline", "Professor of distributed systems",
                "yearsExperience", 12,
                "academicTitle", "Dosent",
                "department", "Faculty of Information Technology",
                "education", "PhD, AzTU, 2012\nMSc, AzTU, 2008",
                "certifications", "AWS Solutions Architect\nCKA",
                "languages", "Azerbaijani, English, Russian",
                "googleScholarUrl", "https://scholar.google.com/citations?user=abc",
                "researchGateUrl", "https://www.researchgate.net/profile/X",
                "orcid", "0000-0002-1825-0097",
                "githubUrl", "https://github.com/aztu");
    }

    private ApiClient.Response patchMe(String token, Map<String, Object> body) {
        return api.patch(ME).bearer(token).json(body).send();
    }

    private ApiClient.Response patchAdmin(String token, UUID profileId, Map<String, Object> body) {
        return api.patch("/api/admin/tutors/" + profileId).bearer(token).json(body).send();
    }

    /** A 400 VALIDATION_FAILED naming {@code field}, the JSON key the dashboard puts the message under. */
    private static void expectFieldError(ApiClient.Response response, String field) {
        response.expectError(400, "VALIDATION_FAILED");
        List<String> fields = new ArrayList<>();
        response.json().path("errors").forEach(item -> fields.add(item.path("field").asText()));
        assertThat(fields).as("field errors in %s", response.body()).contains(field);
    }

    private Map<String, Object> profileRow(UUID profileId) {
        return jdbc.queryForMap("select * from tutor_profiles where id = ?", profileId);
    }

    private UUID avatarInDb(UUID profileId) {
        return jdbc.queryForObject("select avatar_media_id from tutor_profiles where id = ?", UUID.class, profileId);
    }

    private int auditedUpdates(UUID profileId, UUID actorId) {
        return jdbc.queryForObject("""
                select count(*) from audit_logs
                where entity_type = 'TUTOR_PROFILE' and entity_id = ? and action = 'UPDATE' and actor_id = ?
                """, Integer.class, profileId, actorId);
    }
}
