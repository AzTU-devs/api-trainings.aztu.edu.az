package com.eduplatform.eduplatform_backend.tutor;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An expert's own areas of expertise (V22) through every path that writes them: sign-up, which has
 * to carry them through OTP verification, applying from an existing account, and both profile
 * edits. The labels' normalisation itself is pinned in CustomExpertiseRulesTest.
 */
@ExtendWith(OutputCaptureExtension.class)
class CustomExpertiseTest extends AbstractIntegrationTest {

    private static final String ME = "/api/portal/tutor/me";

    @Test
    void signUpCarriesTheApplicantsOwnAreasThroughVerification(CapturedOutput output) {
        String email = uniqueEmail("own-areas");
        UUID category = category("Information Technology " + word());

        api.post("/api/auth/register/tutor/start")
                .json(signUp(email, List.of(category),
                        List.of("  Welding  robotics ", "welding ROBOTICS", "Composite materials")))
                .send().expectStatus(202);
        assertThat(jdbc.queryForObject("select custom_expertise::text from tutor_registration_otps where email = ?",
                String.class, email)).isEqualTo("[\"Welding robotics\", \"Composite materials\"]");

        UUID profileId = UUID.fromString(api.post("/api/auth/register/tutor/verify")
                .json(Json.object("email", email, "otp", otpMailedTo(email, output)))
                .send().expectStatus(201).data().path("tutorId").asText());

        assertThat(jdbc.queryForObject("select custom_expertise::text from tutor_profiles where id = ?",
                String.class, profileId)).isEqualTo("[\"Welding robotics\", \"Composite materials\"]");
        JsonNode mine = api.get(ME).bearer(login(email)).send().expectStatus(200).data();
        assertThat(Json.texts(mine.path("customExpertise"))).containsExactly("Welding robotics", "Composite materials");
        assertThat(Json.texts(mine.path("expertiseCategoryIds"))).containsExactly(category.toString());
    }

    /** Categories may now be left out, as long as the applicant names an area of their own. */
    @Test
    void signUpNeedsAnAreaOfEitherKind(CapturedOutput output) {
        api.post("/api/auth/register/tutor/start")
                .json(signUp(uniqueEmail("no-areas"), List.of(), List.of())).send()
                .expectError(400, "EXPERTISE_REQUIRED");
        api.post("/api/auth/register/tutor/start")
                .json(signUp(uniqueEmail("markup-area"), List.of(), List.of("<b>AI</b>"))).send()
                .expectError(400, "INVALID_CUSTOM_EXPERTISE");

        String email = uniqueEmail("custom-only");
        Map<String, Object> body = signUp(email, List.of(), List.of("Industrial design"));
        body.remove("categoryIds");
        api.post("/api/auth/register/tutor/start").json(body).send().expectStatus(202);
        UUID profileId = UUID.fromString(api.post("/api/auth/register/tutor/verify")
                .json(Json.object("email", email, "otp", otpMailedTo(email, output)))
                .send().expectStatus(201).data().path("tutorId").asText());
        assertThat(jdbc.queryForObject("select count(*) from tutor_expertises where tutor_id = ?",
                Integer.class, profileId)).isZero();
    }

    @Test
    void anExistingAccountAppliesWithItsOwnAreas() {
        String token = login(newUser("applicant", "USER").email());

        api.post("/api/portal/tutor/apply").bearer(token)
                .json(Json.object("headline", "Applicant", "categoryIds", List.of(), "customExpertise", List.of()))
                .send().expectError(400, "EXPERTISE_REQUIRED");
        JsonNode applied = api.post("/api/portal/tutor/apply").bearer(token)
                .json(Json.object("headline", "Applicant", "customExpertise", List.of("Glass blowing")))
                .send().expectStatus(201).data();

        assertThat(Json.texts(applied.path("customExpertise"))).containsExactly("Glass blowing");
        assertThat(Json.texts(applied.path("expertiseCategoryIds"))).isEmpty();
    }

    @Test
    void theCategoriesMayBeEmptiedOnlyWhileAnAreaOfTheirOwnRemains() {
        TestTutor tutor = newApprovedTutor("own-areas-edit");
        String token = login(tutor.email());
        String categoryName = "Mechanics " + word();
        UUID category = category(categoryName);

        JsonNode dto = patchMe(token, Json.object("expertiseCategoryIds", List.of(category),
                "customExpertise", List.of("Tribology", categoryName.toUpperCase(), "tribology")))
                .expectStatus(200).data();
        assertThat(Json.texts(dto.path("customExpertise")))
                .as("the repeat and the label naming a picked category are dropped").containsExactly("Tribology");

        dto = patchMe(token, Json.object("expertiseCategoryIds", List.of())).expectStatus(200).data();
        assertThat(Json.texts(dto.path("expertiseCategoryIds"))).isEmpty();
        assertThat(Json.texts(dto.path("customExpertise"))).containsExactly("Tribology");

        patchMe(token, Json.object("customExpertise", List.of())).expectError(400, "EXPERTISE_REQUIRED");
        patchMe(token, Json.object("customExpertise", List.of("x".repeat(61))))
                .expectError(400, "INVALID_CUSTOM_EXPERTISE");
        // An edit that leaves the areas alone is not held to the rule, and does not touch them.
        dto = patchMe(token, Json.object("headline", "Still fine")).expectStatus(200).data();
        assertThat(Json.texts(dto.path("customExpertise"))).containsExactly("Tribology");
    }

    @Test
    void anAdminEditsTheAreasAndThePublicProfileShowsThem() {
        TestTutor tutor = newApprovedTutor("own-areas-public");
        JsonNode before = api.get("/api/public/tutors/" + tutor.profileId()).send().expectStatus(200).data();
        assertThat(before.path("customExpertise").isArray()).as("always an array: %s", before).isTrue();
        assertThat(before.path("customExpertise").size()).isZero();

        api.patch("/api/admin/tutors/" + tutor.profileId()).bearer(newAdminToken())
                .json(Json.object("customExpertise", List.of("Metrology", " Quality   control "))).send()
                .expectStatus(200);

        JsonNode after = api.get("/api/public/tutors/" + tutor.profileId()).send().expectStatus(200).data();
        assertThat(Json.texts(after.path("customExpertise"))).containsExactly("Metrology", "Quality control");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private ApiClient.Response patchMe(String token, Map<String, Object> body) {
        return api.patch(ME).bearer(token).json(body).send();
    }

    private UUID category(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into categories (id, slug, name, is_active) values (?, ?, ?, true)",
                id, unique("area"), name);
        return id;
    }

    private static Map<String, Object> signUp(String email, List<UUID> categoryIds, List<String> customExpertise) {
        return Json.object("email", email, "password", PASSWORD, "firstName", "Own", "lastName", "Areas",
                "headline", "Teaches things", "categoryIds", categoryIds, "customExpertise", customExpertise);
    }

    /** The OTP from the "[mail:disabled]" line MailService writes instead of sending. */
    private static String otpMailedTo(String email, CapturedOutput output) {
        Matcher otp = Pattern.compile("\\[mail:disabled] To <" + Pattern.quote(email)
                + ">[^\\n]*verification code is: (\\d{6})").matcher(output.getAll());
        assertThat(otp.find()).as("an OTP mail to %s in the log", email).isTrue();
        return otp.group(1);
    }
}
